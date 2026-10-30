import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Resultado de una publicación aceptada por RabbitMQ (`PublishResponse`). */
export interface PublishResult {
  /** Falso si ninguna cola ha recibido el mensaje porque ningún binding coincide; se ha descartado. */
  routed: boolean;
}

/** Tamaño máximo del cuerpo de un mensaje: 64 KB en UTF-8 (CA-7.5). */
export const MAX_BODY_BYTES = 64 * 1024;

/** Publicación de mensajes. Los fallos llegan como `ApiError` con un mensaje en español. */
@Injectable({ providedIn: 'root' })
export class PublishApi {
  private readonly http = inject(HttpClient);

  /** Publica un mensaje de texto; con el exchange por defecto (nombre vacío) la routing key es el nombre de la cola. */
  publish(exchange: string, routingKey: string | null | undefined, body: string): Observable<PublishResult> {
    const request = routingKey ? { exchange, routingKey, body } : { exchange, body };
    return this.http
      .post<PublishResult>('/api/rabbitmq/publications', request)
      .pipe(requestTimeout(), translateApiErrors());
  }
}
