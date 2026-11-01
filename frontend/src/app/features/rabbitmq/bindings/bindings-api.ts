import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { queryString } from '../../../core/http/query';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Enlace entre un exchange y una cola, tal como lo define el contrato (`Binding`). */
export interface Binding {
  exchange: string;
  queue: string;
  /** Routing key o patrón; vacía si el exchange es fanout o se dejó vacía. */
  routingKey: string;
}

/** Resultado de crear un binding (`BindingCreationResponse`). */
export interface BindingCreation {
  binding: Binding;
  /** Falso si ya existía un binding idéntico y no se ha duplicado. */
  created: boolean;
}

const URL = '/api/rabbitmq/bindings';

/** Operaciones sobre bindings. Los fallos llegan como `ApiError` con un mensaje en español. */
@Injectable({ providedIn: 'root' })
export class BindingsApi {
  private readonly http = inject(HttpClient);

  /** Lista los bindings; `exchange` y `queue` filtran si se indican. */
  list(filters: { exchange?: string | null; queue?: string | null } = {}): Observable<Binding[]> {
    return this.http
      .get<Binding[]>(`${URL}${queryString({ exchange: filters.exchange, queue: filters.queue })}`)
      .pipe(requestTimeout(), translateApiErrors());
  }

  /** Crea el binding. La routing key se omite en un fanout, que la ignora. */
  create(exchange: string, queue: string, routingKey?: string | null): Observable<BindingCreation> {
    const body = routingKey ? { exchange, queue, routingKey } : { exchange, queue };
    return this.http.post<BindingCreation>(URL, body).pipe(requestTimeout(), translateApiErrors());
  }

  /** La routing key vacía se envía vacía: es un binding válido y distinto de los demás. */
  delete(exchange: string, queue: string, routingKey: string): Observable<void> {
    return this.http
      .delete<void>(`${URL}${queryString({ exchange, queue, routingKey })}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }
}
