import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';
import { QueueMessage } from '../queues/queues-api';

/** Cómo confirma los mensajes un consumidor (`AckMode`). */
export type AckMode = 'AUTOMATIC' | 'MANUAL';

/** Qué hacer con un mensaje pendiente de confirmar (`MessageAction`). */
export type MessageAction = 'ACK' | 'REQUEUE' | 'REJECT';

/** Qué ha pasado finalmente con el mensaje (`ActionOutcome`). */
export type ActionOutcome = 'CONFIRMED' | 'REQUEUED' | 'DEAD_LETTERED' | 'DISCARDED';

/** Por qué ha terminado un consumidor (`StopReason`). */
export type StopReason = 'USER' | 'QUEUE_DELETED' | 'CONNECTION_LOST' | 'SESSION_CLOSED';

/** Consumidor activo de una sesión (`Consumer`). */
export interface ConsumerInfo {
  id: string;
  name: string;
  queue: string;
  ackMode: AckMode;
}

/** Datos de los eventos del flujo de la sesión (`MessageReceivedEvent`, `ConsumerStoppedEvent`...). */
export interface MessageReceivedEvent {
  consumerId: string;
  deliveryTag: number;
  message: QueueMessage;
}

export interface ConsumerStoppedEvent {
  consumerId: string;
  reason: StopReason;
  message: string;
}

const SESSIONS_URL = '/api/rabbitmq/sessions';

/** Altas, bajas y resolución de mensajes de los consumidores. Los mensajes llegan por el flujo de la sesión. */
@Injectable({ providedIn: 'root' })
export class ConsumersApi {
  private readonly http = inject(HttpClient);

  start(sessionId: string, name: string, queue: string, ackMode: AckMode): Observable<ConsumerInfo> {
    return this.http
      .post<ConsumerInfo>(`${SESSIONS_URL}/${encodeURIComponent(sessionId)}/consumers`, { name, queue, ackMode })
      .pipe(requestTimeout(), translateApiErrors());
  }

  stop(sessionId: string, consumerId: string): Observable<void> {
    return this.http
      .delete<void>(`${SESSIONS_URL}/${encodeURIComponent(sessionId)}/consumers/${encodeURIComponent(consumerId)}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }

  resolve(sessionId: string, consumerId: string, deliveryTag: number, action: MessageAction): Observable<ActionOutcome> {
    return this.http
      .post<{ outcome: ActionOutcome }>(
        `${SESSIONS_URL}/${encodeURIComponent(sessionId)}/consumers/${encodeURIComponent(consumerId)}/messages/${deliveryTag}`,
        { action },
      )
      .pipe(
        requestTimeout(),
        translateApiErrors(),
        map((response) => response.outcome),
      );
  }
}
