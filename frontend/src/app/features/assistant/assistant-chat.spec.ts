import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ASSISTANT_FETCH, AssistantChat } from './assistant-chat';

/** Cuerpo de respuesta en streaming que la prueba va rellenando. */
class FakeStream {
  private readonly chunks: (string | null)[] = [];
  private waiting: ((result: { value?: Uint8Array; done: boolean }) => void) | null = null;

  push(text: string): void {
    this.deliver(text);
  }

  end(): void {
    this.deliver(null);
  }

  getReader() {
    return {
      read: () =>
        new Promise<{ value?: Uint8Array; done: boolean }>((resolve) => {
          const next = this.chunks.shift();
          if (next !== undefined) {
            resolve(toResult(next));
          } else {
            this.waiting = resolve;
          }
        }),
    };
  }

  private deliver(chunk: string | null): void {
    if (this.waiting) {
      const waiting = this.waiting;
      this.waiting = null;
      waiting(toResult(chunk));
    } else {
      this.chunks.push(chunk);
    }
  }
}

function toResult(chunk: string | null): { value?: Uint8Array; done: boolean } {
  return chunk === null ? { done: true } : { value: new TextEncoder().encode(chunk), done: false };
}

const event = (name: string, data: unknown) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`;

describe('AssistantChat', () => {
  let chat: AssistantChat;
  let http: HttpTestingController;
  let stream: FakeStream;
  let requests: { url: string; init: RequestInit }[];
  let respond: (url: string, init: RequestInit) => Promise<unknown>;

  beforeEach(() => {
    requests = [];
    respond = () => Promise.resolve({ ok: true, status: 200, body: stream });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ASSISTANT_FETCH,
          useValue: (url: string, init: RequestInit) => {
            requests.push({ url, init });
            return respond(url, init);
          },
        },
      ],
    });
    chat = TestBed.inject(AssistantChat);
    http = TestBed.inject(HttpTestingController);
    stream = new FakeStream();
  });

  afterEach(() => http.verify());

  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
  const last = () => chat.messages()[chat.messages().length - 1];

  describe('status', () => {
    it('asks the backend for the state of the model', () => {
      chat.refreshStatus();
      http.expectOne('/api/assistant/status').flush({ state: 'PREPARING', message: 'Se está descargando.' });

      expect(chat.status()).toEqual({ state: 'PREPARING', message: 'Se está descargando.' });
      expect(chat.ready()).toBe(false);
    });

    it('is ready only when the model is', () => {
      chat.refreshStatus();
      http.expectOne('/api/assistant/status').flush({ state: 'READY' });

      expect(chat.ready()).toBe(true);
    });

    it('is unavailable with the reason when the backend does not answer', () => {
      chat.refreshStatus();
      http.expectOne('/api/assistant/status').error(new ProgressEvent('error'), { status: 0 });

      expect(chat.status()).toEqual({ state: 'UNAVAILABLE', message: 'El backend no está disponible.' });
    });
  });

  describe('asking', () => {
    it('sends the question to the chat endpoint as JSON', async () => {
      const done = chat.ask('  ¿Qué es un binding?  ');
      stream.push(event('done', {}));
      stream.end();
      await done;

      expect(requests[0].url).toBe('/api/assistant/chat');
      expect(requests[0].init.method).toBe('POST');
      expect(JSON.parse(requests[0].init.body as string)).toEqual({ question: '¿Qué es un binding?', history: [] });
    });

    it('shows the answer piece by piece as it arrives', async () => {
      const done = chat.ask('Hola');
      await flush();
      expect(chat.answering()).toBe(true);
      expect(chat.messages()).toEqual([{ role: 'USER', text: 'Hola' }, { role: 'ASSISTANT', text: '' }]);

      stream.push(event('token', { text: 'Todo ' }));
      await flush();
      expect(last().text).toBe('Todo ');

      stream.push(event('token', { text: 'bien.' }) + event('done', {}));
      stream.end();
      await done;

      expect(last()).toEqual({ role: 'ASSISTANT', text: 'Todo bien.' });
      expect(chat.answering()).toBe(false);
    });

    it('copes with events split across chunks and with CRLF line endings', async () => {
      const done = chat.ask('Hola');
      const text = event('token', { text: 'uno' }).replace(/\n/g, '\r\n');
      stream.push(text.slice(0, 7));
      stream.push(text.slice(7) + event('done', {}));
      stream.end();
      await done;

      expect(last().text).toBe('uno');
    });

    it('sends the earlier turns as history, without the failed or cancelled ones', async () => {
      chat.messages.set([
        { role: 'USER', text: '¿Qué es una cola?' },
        { role: 'ASSISTANT', text: 'Guarda mensajes.' },
        { role: 'USER', text: 'falló' },
        { role: 'ASSISTANT', text: '', error: 'Error' },
      ]);
      const done = chat.ask('¿Y un binding?');
      stream.push(event('done', {}));
      stream.end();
      await done;

      expect(JSON.parse(requests[0].init.body as string).history).toEqual([
        { role: 'USER', text: '¿Qué es una cola?' },
        { role: 'ASSISTANT', text: 'Guarda mensajes.' },
        { role: 'USER', text: 'falló' },
      ]);
    });

    it('ignores an empty question, one too long, or a second one while answering', async () => {
      await chat.ask('   ');
      await chat.ask('a'.repeat(501));
      expect(requests).toHaveLength(0);

      const first = chat.ask('Hola');
      await chat.ask('Otra');
      expect(requests).toHaveLength(1);
      stream.end();
      await first;
    });
  });

  describe('when something goes wrong', () => {
    it('keeps what arrived and shows the error the server sends in the middle', async () => {
      const done = chat.ask('Hola');
      stream.push(event('token', { text: 'Medio ' }) + event('error', { message: 'El modelo ha dejado de responder.' }));
      stream.end();
      await done;

      expect(last()).toEqual({ role: 'ASSISTANT', text: 'Medio ', error: 'El modelo ha dejado de responder.' });
    });

    it('says so when the stream ends without finishing', async () => {
      const done = chat.ask('Hola');
      stream.push(event('token', { text: 'a' }));
      stream.end();
      await done;

      expect(last().error).toBe('La respuesta se ha cortado antes de terminar.');
    });

    it('shows the message of a refused question and checks the status again when the model is not ready', async () => {
      respond = () =>
        Promise.resolve({ ok: false, status: 503, body: null, json: () => Promise.resolve({ message: 'Se está descargando el modelo.' }) });

      await chat.ask('Hola');

      expect(last().error).toBe('Se está descargando el modelo.');
      http.expectOne('/api/assistant/status').flush({ state: 'PREPARING' });
    });

    it('shows the message of a busy assistant', async () => {
      respond = () =>
        Promise.resolve({ ok: false, status: 409, body: null, json: () => Promise.resolve({ message: 'El asistente está ocupado.' }) });

      await chat.ask('Hola');

      expect(last().error).toBe('El asistente está ocupado.');
    });

    it('says the backend is unavailable when the request cannot be made', async () => {
      respond = () => Promise.reject(new TypeError('Failed to fetch'));

      await chat.ask('Hola');

      expect(last().error).toBe('El backend no está disponible.');
      expect(chat.answering()).toBe(false);
    });
  });

  describe('cancelling and starting again', () => {
    it('cancels the answer in progress and keeps what arrived', async () => {
      respond = (_url, init) =>
        new Promise((resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        });
      const done = chat.ask('Hola');
      await flush();

      chat.cancel();
      await done;

      expect(last().cancelled).toBe(true);
      expect(chat.answering()).toBe(false);
    });

    it('marks as cancelled an answer whose stream is cut by the cancellation', async () => {
      const done = chat.ask('Hola');
      stream.push(event('token', { text: 'parte' }));
      await flush();

      chat.cancel();
      stream.end();
      await done;

      expect(last()).toEqual({ role: 'ASSISTANT', text: 'parte', cancelled: true });
    });

    it('starts a new conversation', async () => {
      const done = chat.ask('Hola');
      stream.push(event('done', {}));
      stream.end();
      await done;

      chat.reset();

      expect(chat.messages()).toEqual([]);
    });
  });

  it('keeps the conversation while the app is open, because it lives at application level', async () => {
    const done = chat.ask('Hola');
    stream.push(event('done', {}));
    stream.end();
    await done;

    expect(TestBed.inject(AssistantChat).messages()).toHaveLength(2);
  });
});
