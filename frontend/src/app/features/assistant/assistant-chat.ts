import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { ApiError, BACKEND_UNAVAILABLE_MESSAGE, translateApiErrors } from '../../core/http/api-error';
import { requestTimeout } from '../../core/http/request-timeout';

/** Estado del modelo del asistente (`AssistantStatus` del contrato). */
export interface AssistantStatus {
  state: 'READY' | 'PREPARING' | 'UNAVAILABLE';
  message?: string | null;
}

export interface ChatMessage {
  role: 'USER' | 'ASSISTANT';
  text: string;
  /** Qué falló, si la respuesta no pudo completarse. */
  error?: string;
  cancelled?: boolean;
}

export const MAX_QUESTION = 500;
/** Intervenciones anteriores que se envían con cada pregunta; el servidor se queda con las que caben. */
export const HISTORY_SIZE = 8;

/** `fetch` del navegador; se sustituye en las pruebas. Se usa porque `EventSource` solo permite GET. */
export const ASSISTANT_FETCH = new InjectionToken<typeof fetch>('AssistantFetch', {
  providedIn: 'root',
  factory: () => (input, init) => fetch(input, init),
});

const GENERIC_ERROR = 'El asistente ha fallado. Inténtalo de nuevo en un momento.';

/**
 * La conversación con el asistente. Es global para que el historial sobreviva al cerrar el panel o cambiar de
 * pantalla, y no se guarda en ningún otro sitio (CA-1.4).
 */
@Injectable({ providedIn: 'root' })
export class AssistantChat {
  private readonly http = inject(HttpClient);
  private readonly fetch = inject(ASSISTANT_FETCH);
  private abort: AbortController | null = null;

  readonly status = signal<AssistantStatus | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly answering = signal(false);
  readonly ready = computed(() => this.status()?.state === 'READY');

  refreshStatus(): void {
    this.http
      .get<AssistantStatus>('/api/assistant/status')
      .pipe(requestTimeout(), translateApiErrors())
      .subscribe({
        next: (status) => this.status.set(status),
        error: (error: ApiError) => this.status.set({ state: 'UNAVAILABLE', message: error.message }),
      });
  }

  /** Envía la pregunta y va añadiendo la respuesta a medida que llega. */
  async ask(question: string): Promise<void> {
    const text = question.trim();
    if (!text || text.length > MAX_QUESTION || this.answering()) {
      return;
    }
    const history = this.messages()
      .filter((message) => !message.error && !message.cancelled && message.text)
      .slice(-HISTORY_SIZE)
      .map(({ role, text }) => ({ role, text }));
    this.messages.update((list) => [...list, { role: 'USER', text }, { role: 'ASSISTANT', text: '' }]);
    this.answering.set(true);
    const abort = new AbortController();
    this.abort = abort;
    try {
      const response = await this.fetch('/api/assistant/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ question: text, history }),
        signal: abort.signal,
      });
      if (!response.ok || !response.body) {
        this.finish({ error: await errorMessage(response) });
        if (response.status === 503) {
          this.refreshStatus();
        }
        return;
      }
      await this.read(response.body, abort.signal);
    } catch {
      this.finish(abort.signal.aborted ? { cancelled: true } : { error: BACKEND_UNAVAILABLE_MESSAGE });
    } finally {
      if (this.abort === abort) {
        this.abort = null;
      }
      this.answering.set(false);
    }
  }

  /** Detiene la respuesta en curso; se conserva lo recibido. */
  cancel(): void {
    this.abort?.abort();
  }

  /** Empieza una conversación nueva. */
  reset(): void {
    this.cancel();
    this.messages.set([]);
  }

  /** Lee el flujo `text/event-stream`: bloques separados por una línea en blanco, con `event:` y `data:`. */
  private async read(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let ended = false;
    while (!ended) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
      let cut: number;
      while ((cut = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, cut);
        buffer = buffer.slice(cut + 2);
        ended = this.handle(block) || ended;
      }
    }
    if (signal.aborted) {
      this.finish({ cancelled: true });
    } else if (!ended) {
      this.finish({ error: 'La respuesta se ha cortado antes de terminar.' });
    }
  }

  /** @returns si el bloque cierra la respuesta */
  private handle(block: string): boolean {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''));
      }
    }
    const payload = parse(data.join('\n'));
    if (event === 'token' && typeof payload?.['text'] === 'string') {
      const piece = payload['text'];
      this.updateLast((last) => ({ ...last, text: last.text + piece }));
    } else if (event === 'error') {
      this.finish({ error: typeof payload?.['message'] === 'string' ? payload['message'] : GENERIC_ERROR });
      return true;
    } else if (event === 'done') {
      return true;
    }
    return false;
  }

  private finish(change: Partial<ChatMessage>): void {
    this.updateLast((last) => ({ ...last, ...change }));
  }

  private updateLast(change: (last: ChatMessage) => ChatMessage): void {
    this.messages.update((list) => (list.length ? [...list.slice(0, -1), change(list[list.length - 1])] : list));
  }
}

function parse(text: string): Record<string, unknown> | null {
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: unknown };
    if (typeof body.message === 'string') {
      return body.message;
    }
  } catch {
    // sin cuerpo legible
  }
  return response.status === 0 || response.status >= 502 ? BACKEND_UNAVAILABLE_MESSAGE : GENERIC_ERROR;
}
