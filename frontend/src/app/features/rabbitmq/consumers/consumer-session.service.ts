import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, defer, from, map, switchMap, tap, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { DeathInfo } from '../queues/queues-api';
import {
  AckMode,
  ActionOutcome,
  ConsumerInfo,
  ConsumerStoppedEvent,
  ConsumersApi,
  MessageAction,
  MessageReceivedEvent,
  StopReason,
} from './consumers-api';
import { EVENT_SOURCE_FACTORY, EventSourceLike } from './event-source-factory';

/** Mensajes que se conservan en pantalla por consumidor; los anteriores se descartan (no se guardan en ningún sitio). */
export const MAX_MESSAGES_PER_CONSUMER = 100;

const EVENTS_URL = '/api/rabbitmq/session/events';

const STREAM_LOST_MESSAGE =
  'Se ha perdido la comunicación con el servidor: los consumidores se han detenido y sus mensajes pendientes de confirmar volverán a la cola.';
const STREAM_NOT_OPENED_MESSAGE =
  'No se ha podido abrir la conexión para recibir mensajes. Los consumidores no se han iniciado.';

/**
 * - `pending`: recibido y a la espera de que el usuario decida (solo en modo manual).
 * - `resolving`: hay una acción en curso; no se admite otra (CA-9.10).
 * - `resolved`: ya resuelto, con su desenlace.
 * - `automatic`: el consumidor automático lo confirmó al recibirlo.
 */
export type MessageState = 'pending' | 'resolving' | 'resolved' | 'automatic';

export interface ReceivedMessage {
  /** Identifica el mensaje dentro de su consumidor. */
  deliveryTag: number;
  body: string;
  exchange: string;
  routingKey: string;
  redelivered: boolean;
  deathInfo?: DeathInfo | null;
  state: MessageState;
  outcome?: ActionOutcome;
  /** Fallo al resolverlo, si lo hubo. */
  error?: string;
}

export interface ConsumerView {
  info: ConsumerInfo;
  active: boolean;
  stopReason?: StopReason;
  /** Explicación en español de por qué terminó. */
  stopMessage?: string;
  /** Del más antiguo al más reciente, con un máximo de {@link MAX_MESSAGES_PER_CONSUMER}. */
  messages: ReceivedMessage[];
}

export type StreamState = 'idle' | 'connecting' | 'open' | 'broken';

/**
 * Sesión de consumidores del laboratorio: un único flujo de eventos (`EventSource`) por pestaña que reparte
 * los mensajes de todos sus consumidores. Se provee en la ruta del laboratorio, de modo que sigue viva al
 * cambiar de área y se destruye al salir, cerrando el flujo: el servidor detiene entonces los consumidores y
 * RabbitMQ devuelve a la cola lo pendiente (CA-9.13).
 *
 * El flujo no se reconecta solo: una sesión nueva no conserva los consumidores de la anterior (D-10).
 */
@Injectable()
export class ConsumerSessionService {
  private readonly api = inject(ConsumersApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly createEventSource = inject(EVENT_SOURCE_FACTORY);

  private readonly consumerViews = signal<ConsumerView[]>([]);
  private readonly stream = signal<StreamState>('idle');
  private readonly streamProblem = signal<string | null>(null);

  readonly consumers = this.consumerViews.asReadonly();
  readonly streamState = this.stream.asReadonly();
  /** Aviso en español cuando el flujo se corta o no se pudo abrir. */
  readonly streamError = this.streamProblem.asReadonly();
  readonly activeConsumers = computed(() => this.consumerViews().filter((consumer) => consumer.active));

  private source: EventSourceLike | null = null;
  private sessionId: string | null = null;
  private opening: Promise<string> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.closeStream());
  }

  /** Inicia un consumidor; el flujo se abre con el primero (CA-9.1). */
  start(name: string, queue: string, ackMode: AckMode): Observable<ConsumerInfo> {
    return defer(() => from(this.ensureSession())).pipe(
      switchMap((sessionId) => this.api.start(sessionId, name, queue, ackMode)),
      tap((info) => this.addConsumer(info)),
      catchError((error: unknown) => throwError(() => this.report(error))),
    );
  }

  /** Detiene un consumidor; sus mensajes pendientes de confirmar vuelven a la cola (CA-9.8). */
  stop(consumerId: string): Observable<void> {
    return this.withSession().pipe(
      switchMap((sessionId) => this.api.stop(sessionId, consumerId)),
      tap(() =>
        this.markStopped(consumerId, 'USER', `El consumidor «${this.nameOf(consumerId)}» se ha detenido.`),
      ),
      catchError((error: unknown) => throwError(() => this.report(error))),
    );
  }

  /** Aplica una acción a un mensaje pendiente; no admite otra mientras haya una en curso (CA-9.10). */
  resolve(consumerId: string, deliveryTag: number, action: MessageAction): Observable<ActionOutcome> {
    const message = this.find(consumerId, deliveryTag);
    if (!message || message.state !== 'pending') {
      return throwError(() => new ApiError('conflict', 'Este mensaje ya no está pendiente de resolver.'));
    }
    this.updateMessage(consumerId, deliveryTag, { state: 'resolving', error: undefined });
    return this.withSession().pipe(
      switchMap((sessionId) => this.api.resolve(sessionId, consumerId, deliveryTag, action)),
      tap((outcome) => this.updateMessage(consumerId, deliveryTag, { state: 'resolved', outcome })),
      catchError((error: unknown) => {
        const apiError = this.report(error);
        // un conflicto es que ya estaba resuelto; cualquier otro fallo deja el mensaje pendiente para reintentar
        this.updateMessage(
          consumerId,
          deliveryTag,
          apiError.kind === 'conflict'
            ? { state: 'resolved', error: apiError.message }
            : { state: 'pending', error: apiError.message },
        );
        return throwError(() => apiError);
      }),
    );
  }

  /** Detiene todos los consumidores cerrando el flujo: RabbitMQ devuelve a la cola lo pendiente. */
  stopAll(): void {
    this.closeStream();
    this.consumerViews.update((consumers) =>
      consumers.map((consumer) =>
        consumer.active
          ? {
              ...consumer,
              active: false,
              stopReason: 'SESSION_CLOSED',
              stopMessage: `El consumidor «${consumer.info.name}» se ha detenido.`,
            }
          : consumer,
      ),
    );
  }

  /** Abre una sesión nueva tras un corte del flujo. Los consumidores anteriores no se recuperan. */
  reconnect(): Observable<void> {
    return defer(() => {
      this.streamProblem.set(null);
      this.closeStream();
      return from(this.ensureSession());
    }).pipe(
      map(() => undefined),
      catchError((error: unknown) => throwError(() => this.report(error))),
    );
  }

  private ensureSession(): Promise<string> {
    if (this.sessionId && this.stream() === 'open') {
      return Promise.resolve(this.sessionId);
    }
    this.opening ??= this.openStream();
    return this.opening;
  }

  private withSession(): Observable<string> {
    return this.sessionId && this.stream() === 'open'
      ? from(Promise.resolve(this.sessionId))
      : throwError(() => new ApiError('not-found', 'La sesión no existe o ya ha terminado.'));
  }

  private openStream(): Promise<string> {
    this.stream.set('connecting');
    return new Promise<string>((resolve, reject) => {
      const source = this.createEventSource(EVENTS_URL);
      this.source = source;

      source.addEventListener('session-started', (event) => {
        const data = this.parse<{ sessionId: string }>(event);
        if (!data?.sessionId) {
          return;
        }
        this.sessionId = data.sessionId;
        this.streamProblem.set(null);
        this.stream.set('open');
        this.opening = null;
        resolve(data.sessionId);
      });
      source.addEventListener('consumer-started', (event) => {
        const data = this.parse<{ consumer: ConsumerInfo }>(event);
        if (data?.consumer) {
          this.addConsumer(data.consumer);
        }
      });
      source.addEventListener('message-received', (event) => {
        const data = this.parse<MessageReceivedEvent>(event);
        if (data) {
          this.receive(data);
        }
      });
      source.addEventListener('consumer-stopped', (event) => {
        const data = this.parse<ConsumerStoppedEvent>(event);
        if (data) {
          this.markStopped(data.consumerId, data.reason, data.message);
        }
      });
      // EventSource reintentaría solo; se cierra para que no haya reconexión automática (D-10)
      source.onerror = () => {
        const wasOpen = this.stream() === 'open';
        this.breakStream(wasOpen ? STREAM_LOST_MESSAGE : STREAM_NOT_OPENED_MESSAGE);
        reject(new ApiError('backend-unavailable', STREAM_NOT_OPENED_MESSAGE));
      };
    });
  }

  private breakStream(message: string): void {
    this.closeStream();
    this.stream.set('broken');
    this.streamProblem.set(message);
    this.consumerViews.update((consumers) =>
      consumers.map((consumer) =>
        consumer.active
          ? { ...consumer, active: false, stopReason: 'CONNECTION_LOST', stopMessage: STREAM_LOST_MESSAGE }
          : consumer,
      ),
    );
  }

  private closeStream(): void {
    if (this.source) {
      this.source.onerror = null;
      this.source.close();
    }
    this.source = null;
    this.sessionId = null;
    this.opening = null;
    if (this.stream() !== 'broken') {
      this.stream.set('idle');
    }
  }

  private addConsumer(info: ConsumerInfo): void {
    this.consumerViews.update((consumers) =>
      consumers.some((consumer) => consumer.info.id === info.id)
        ? consumers
        : [...consumers, { info, active: true, messages: [] }],
    );
  }

  private receive(event: MessageReceivedEvent): void {
    this.consumerViews.update((consumers) =>
      consumers.map((consumer) => {
        if (consumer.info.id !== event.consumerId) {
          return consumer;
        }
        const message: ReceivedMessage = {
          deliveryTag: event.deliveryTag,
          body: event.message.body,
          exchange: event.message.exchange,
          routingKey: event.message.routingKey,
          redelivered: event.message.redelivered,
          deathInfo: event.message.deathInfo,
          state: consumer.info.ackMode === 'MANUAL' ? 'pending' : 'automatic',
        };
        return { ...consumer, messages: [...consumer.messages, message].slice(-MAX_MESSAGES_PER_CONSUMER) };
      }),
    );
  }

  private markStopped(consumerId: string, reason: StopReason, message: string): void {
    this.consumerViews.update((consumers) =>
      consumers.map((consumer) =>
        consumer.info.id === consumerId && consumer.active
          ? { ...consumer, active: false, stopReason: reason, stopMessage: message }
          : consumer,
      ),
    );
  }

  private updateMessage(consumerId: string, deliveryTag: number, changes: Partial<ReceivedMessage>): void {
    this.consumerViews.update((consumers) =>
      consumers.map((consumer) =>
        consumer.info.id === consumerId
          ? {
              ...consumer,
              messages: consumer.messages.map((message) =>
                message.deliveryTag === deliveryTag ? { ...message, ...changes } : message,
              ),
            }
          : consumer,
      ),
    );
  }

  private find(consumerId: string, deliveryTag: number): ReceivedMessage | undefined {
    return this.consumerViews()
      .find((consumer) => consumer.info.id === consumerId)
      ?.messages.find((message) => message.deliveryTag === deliveryTag);
  }

  private nameOf(consumerId: string): string {
    return this.consumerViews().find((consumer) => consumer.info.id === consumerId)?.info.name ?? consumerId;
  }

  private parse<T>(event: MessageEvent<string>): T | null {
    try {
      return JSON.parse(event.data) as T;
    } catch {
      return null;
    }
  }

  private report(error: unknown): ApiError {
    const apiError = error instanceof ApiError ? error : new ApiError('unknown', 'No se ha podido completar la operación.');
    this.connection.reportFailure(apiError);
    return apiError;
  }
}
