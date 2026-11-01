import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { queryString } from '../../../core/http/query';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Cola con sus contadores, tal como la define el contrato (`Queue`). */
export interface Queue {
  name: string;
  /** Cola a la que van los mensajes rechazados sin reencolar; ausente o nula si no tiene. */
  deadLetterQueue?: string | null;
  ready: number;
  unacknowledged: number;
  total: number;
}

/** Resultado de crear una cola (`QueueCreationResponse`). */
export interface QueueCreation {
  queue: Queue;
  /** Falso si ya existía una cola idéntica y no se ha duplicado. */
  created: boolean;
}

/** Por qué un mensaje de una dead-letter queue llegó hasta ella (`DeathInfo`). */
export interface DeathInfo {
  originQueue: string;
  reason: string;
}

/** Mensaje de una cola (`QueueMessage`). */
export interface QueueMessage {
  body: string;
  exchange: string;
  routingKey: string;
  redelivered: boolean;
  deathInfo?: DeathInfo | null;
}

/** Resultado de inspeccionar una cola (`QueueInspection`). */
export interface QueueInspection {
  messages: QueueMessage[];
  /** Mensajes listos que tiene la cola. */
  total: number;
  /** Hay más mensajes listos de los que se muestran. */
  truncated: boolean;
}

const URL = '/api/rabbitmq/queues';
const MESSAGES_URL = `${URL}/messages`;

/** Operaciones sobre colas. Los fallos llegan como `ApiError` con un mensaje en español. */
@Injectable({ providedIn: 'root' })
export class QueuesApi {
  private readonly http = inject(HttpClient);

  /** Lista las colas con sus contadores actuales. */
  list(): Observable<Queue[]> {
    return this.http.get<Queue[]>(URL).pipe(requestTimeout(), translateApiErrors());
  }

  /** Crea una cola durable; la dead-letter queue es opcional y debe ser una cola existente. */
  create(name: string, deadLetterQueue?: string | null): Observable<QueueCreation> {
    const body = deadLetterQueue ? { name, deadLetterQueue } : { name };
    return this.http.post<QueueCreation>(URL, body).pipe(requestTimeout(), translateApiErrors());
  }

  /** El nombre va en la consulta porque puede contener caracteres como «/». */
  delete(name: string): Observable<void> {
    return this.http
      .delete<void>(`${URL}${queryString({ name })}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }

  /** Elimina los mensajes listos de la cola, manteniendo la cola y sus bindings. */
  purge(name: string): Observable<void> {
    return this.http
      .delete<void>(`${MESSAGES_URL}${queryString({ queue: name })}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }

  /** Lee los primeros mensajes listos de la cola sin retirarlos. */
  inspect(name: string): Observable<QueueInspection> {
    return this.http
      .get<QueueInspection>(`${MESSAGES_URL}${queryString({ queue: name })}`)
      .pipe(requestTimeout(), translateApiErrors());
  }
}
