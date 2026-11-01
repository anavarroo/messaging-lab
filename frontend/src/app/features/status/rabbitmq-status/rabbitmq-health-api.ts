import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Estado del trazado de RabbitMQ, que es lo que alimenta el visualizador de flujo (`FlowSourceStatus`). */
export interface TracingStatus {
  state: 'ACTIVE' | 'DISABLED' | 'UNAVAILABLE';
  /** Cómo activarlo, en español, cuando está desactivado. */
  hint?: string | null;
}

/** Consultas de salud de RabbitMQ para la pantalla de estado. Los fallos llegan como `ApiError` en español. */
@Injectable({ providedIn: 'root' })
export class RabbitMqHealthApi {
  private readonly http = inject(HttpClient);

  /** Versión de RabbitMQ si el backend puede comunicarse con él; si no, falla con el motivo. */
  version(): Observable<string> {
    return this.http
      .get<{ version: string }>('/api/rabbitmq/status')
      .pipe(requestTimeout(), translateApiErrors(), map((status) => status.version));
  }

  tracing(): Observable<TracingStatus> {
    return this.http.get<TracingStatus>('/api/rabbitmq/flow/status').pipe(requestTimeout(), translateApiErrors());
  }

}
