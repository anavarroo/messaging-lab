import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangesApi } from '../exchanges/exchanges-api';
import { Queue, QueuesApi } from '../queues/queues-api';
import { MAX_BODY_BYTES, PublishApi, PublishResult } from './publish-api';
import { PublishPage } from './publish-page';

describe('PublishPage', () => {
  const exchanges: Exchange[] = [
    { name: 'directo', type: 'DIRECT', predefined: false },
    { name: 'difusion', type: 'FANOUT', predefined: false },
    { name: 'temas', type: 'TOPIC', predefined: false },
    { name: '', type: 'DIRECT', predefined: true },
  ];
  const queues: Queue[] = [{ name: 'pedidos', ready: 0, unacknowledged: 0, total: 0 }];

  let fixture: ComponentFixture<PublishPage>;
  let page: HTMLElement;
  let publish: ReturnType<typeof vi.fn<(e: string, k: string | null | undefined, b: string) => Observable<PublishResult>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    publish = vi.fn(() => of({ routed: true }));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [PublishPage],
      providers: [
        { provide: PublishApi, useValue: { publish } },
        { provide: ExchangesApi, useValue: { list: () => of(exchanges) } },
        { provide: QueuesApi, useValue: { list: () => of(queues) } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PublishPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = <T extends HTMLElement>(id: string) => page.querySelector<T>(`#${id}`);
  const submitButton = () => page.querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const result = () => page.querySelector('.result')?.textContent?.replace(/\s+/g, ' ').trim();

  async function choose(exchange: string) {
    const select = el<HTMLSelectElement>('publish-exchange')!;
    select.value = exchange;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  async function type(id: string, value: string) {
    const input = el<HTMLInputElement | HTMLTextAreaElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit() {
    submitButton().click();
    await settle();
  }

  it('offers every exchange, the default one included, with its type', () => {
    const options = Array.from(el<HTMLSelectElement>('publish-exchange')!.options).map((o) => o.textContent?.trim());

    expect(options).toEqual([
      'Elige un exchange',
      'directo (Direct)',
      'difusion (Fanout)',
      'temas (Topic)',
      '(exchange por defecto) (Direct)',
    ]);
  });

  it('starts with no exchange chosen, not with the default one', () => {
    expect(el('publish-key')).toBeNull();
    expect(el<HTMLSelectElement>('publish-exchange')!.selectedOptions[0].textContent?.trim()).toBe('Elige un exchange');
  });

  it('requires choosing an exchange', async () => {
    await type('publish-body', 'hola');
    await submit();

    expect(el('publish-exchange-error')?.textContent?.trim()).toBe('Elige el exchange en el que publicar.');
    expect(publish).not.toHaveBeenCalled();
  });

  describe('routing key by exchange type', () => {
    it('asks for a routing key in direct and topic exchanges', async () => {
      await choose('directo');
      expect(page.querySelector('label[for="publish-key"]')?.textContent?.trim()).toBe('Routing key');
      await choose('temas');
      expect(page.querySelector('label[for="publish-key"]')?.textContent?.trim()).toBe('Routing key');
    });

    it('hides it in a fanout and says it does not use one', async () => {
      await choose('difusion');

      expect(el('publish-key')).toBeNull();
      expect(page.textContent).toContain('no usa routing key');
    });

    it('uses the queue name as routing key for the default exchange, suggesting the queues (CA-7.7)', async () => {
      await choose('');

      expect(page.querySelector('label[for="publish-key"]')?.textContent?.trim()).toBe('Cola de destino (routing key)');
      expect(el('publish-key-help')?.textContent).toContain('cola cuyo nombre es esta routing key');
      const suggestions = Array.from(page.querySelectorAll('#publish-queues option')).map((o) => o.getAttribute('value'));
      expect(suggestions).toEqual(['pedidos']);
    });

    it('rejects a routing key over 255 characters', async () => {
      await choose('directo');
      await type('publish-key', 'a'.repeat(256));
      await type('publish-body', 'hola');
      await submit();

      expect(el('publish-key-error')?.textContent).toContain('no puede superar los 255 caracteres');
      expect(publish).not.toHaveBeenCalled();
    });
  });

  describe('body validation', () => {
    beforeEach(async () => {
      await choose('directo');
      await type('publish-key', 'alta');
    });

    it('rejects an empty body without publishing (CA-7.5)', async () => {
      await submit();

      expect(el('publish-body-error')?.textContent?.trim()).toBe('El cuerpo del mensaje no puede estar vacío.');
      expect(el('publish-body')?.getAttribute('aria-invalid')).toBe('true');
      expect(publish).not.toHaveBeenCalled();
    });

    it('accepts a body of exactly 64 KB', async () => {
      await type('publish-body', 'x'.repeat(MAX_BODY_BYTES));
      await submit();

      expect(publish).toHaveBeenCalledTimes(1);
    });

    it('rejects a body of 64 KB plus one byte, and says so as soon as it is typed', async () => {
      await type('publish-body', 'x'.repeat(MAX_BODY_BYTES + 1));
      expect(el('publish-body-error')?.textContent).toContain('no puede ocupar más de 64 KB');

      await submit();

      expect(publish).not.toHaveBeenCalled();
    });

    it('counts bytes in UTF-8, so multibyte characters weigh more', async () => {
      await type('publish-body', 'ñ'.repeat(MAX_BODY_BYTES / 2 + 1));
      expect(el('publish-body-error')?.textContent).toContain('64 KB');

      await type('publish-body', 'ñ'.repeat(MAX_BODY_BYTES / 2));
      expect(el('publish-body-error')?.textContent?.trim()).toBe('');
      expect(el('publish-body-size')?.textContent).toContain('65536 de 65536 bytes');
    });
  });

  describe('publishing', () => {
    it('publishes to a direct exchange and says the message was routed (CA-7.3)', async () => {
      await choose('directo');
      await type('publish-key', 'alta');
      await type('publish-body', '{"id": 42}');

      await submit();

      expect(publish).toHaveBeenCalledWith('directo', 'alta', '{"id": 42}');
      expect(result()).toBe('El mensaje se ha publicado y se ha enrutado al menos a una cola.');
    });

    it('tells explicitly that the message could not be routed and was discarded (CA-7.4)', async () => {
      publish.mockReturnValue(of({ routed: false }));
      await choose('directo');
      await type('publish-key', 'nadie');
      await type('publish-body', 'hola');

      await submit();

      expect(result()).toContain('no se ha podido enrutar a ninguna cola y se ha descartado');
      expect(page.querySelector('.notice')).toBeNull();
    });

    it('publishes to a fanout without routing key', async () => {
      await choose('difusion');
      await type('publish-body', 'a todos');

      await submit();

      expect(publish).toHaveBeenCalledWith('difusion', '', 'a todos');
    });

    it('publishes to the default exchange using the queue name as routing key (CA-7.7)', async () => {
      await choose('');
      await type('publish-key', 'pedidos');
      await type('publish-body', 'directo a la cola');

      await submit();

      expect(publish).toHaveBeenCalledWith('', 'pedidos', 'directo a la cola');
    });

    it('allows an empty routing key', async () => {
      await choose('temas');
      await type('publish-body', 'sin clave');

      await submit();

      expect(publish).toHaveBeenCalledWith('temas', '', 'sin clave');
    });

    it('sends the body exactly as typed, markup included (CL-17)', async () => {
      await choose('directo');
      await type('publish-body', '<script>alert(1)</script> & "ñ"');

      await submit();

      expect(publish).toHaveBeenCalledWith('directo', '', '<script>alert(1)</script> & "ñ"');
    });

    it('keeps the typed content when the publication fails, and shows the error (CA-7.6)', async () => {
      const failure = new ApiError('not-found', 'El exchange «directo» no existe.');
      publish.mockReturnValue(throwError(() => failure));
      await choose('directo');
      await type('publish-key', 'alta');
      await type('publish-body', 'no quiero perderlo');

      await submit();

      expect(alerts()).toContain('El exchange «directo» no existe.');
      expect(el<HTMLTextAreaElement>('publish-body')!.value).toBe('no quiero perderlo');
      expect(el<HTMLInputElement>('publish-key')!.value).toBe('alta');
      expect(reportFailure).toHaveBeenCalledWith(failure);
      expect(result()).toBe('');
    });

    it('clears the previous result when publishing again', async () => {
      await choose('directo');
      await type('publish-body', 'uno');
      await submit();
      expect(result()).toContain('enrutado');
      publish.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));

      await submit();

      expect(result()).toBe('');
    });

    it('blocks the button while sending and ignores repeats (CA-7.8)', async () => {
      const pending = new Subject<PublishResult>();
      publish.mockReturnValue(pending);
      await choose('directo');
      await type('publish-body', 'hola');

      await submit();
      expect(submitButton().disabled).toBe(true);
      expect(submitButton().textContent).toContain('Publicando');
      submitButton().click();
      await settle();
      expect(publish).toHaveBeenCalledTimes(1);

      pending.next({ routed: true });
      await settle();
      expect(submitButton().disabled).toBe(false);
      expect(submitButton().textContent?.trim()).toBe('Publicar');
    });
  });

  it('shows the error when the exchanges cannot be loaded', async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [PublishPage],
      providers: [
        { provide: PublishApi, useValue: { publish } },
        {
          provide: ExchangesApi,
          useValue: { list: () => throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')) },
        },
        { provide: QueuesApi, useValue: { list: () => of(queues) } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PublishPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();

    expect(alerts()).toContain('RabbitMQ no está disponible.');
    expect(reportFailure).toHaveBeenCalled();
  });
});
