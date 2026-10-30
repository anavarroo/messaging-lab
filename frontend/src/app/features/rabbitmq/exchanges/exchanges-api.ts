import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { queryString } from '../../../core/http/query';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Tipos de exchange que admite el laboratorio. */
export type ExchangeType = 'DIRECT' | 'FANOUT' | 'TOPIC';

/** Exchange tal como lo define el contrato (`Exchange`). */
export interface Exchange {
  name: string;
  type: ExchangeType;
  predefined: boolean;
}

/** Resultado de crear un exchange (`ExchangeCreationResponse`). */
export interface ExchangeCreation {
  exchange: Exchange;
  /** Falso si ya existía uno idéntico y no se ha duplicado. */
  created: boolean;
}

const URL = '/api/rabbitmq/exchanges';

/** Operaciones sobre exchanges. Los fallos llegan como `ApiError` con un mensaje en español. */
@Injectable({ providedIn: 'root' })
export class ExchangesApi {
  private readonly http = inject(HttpClient);

  list(): Observable<Exchange[]> {
    return this.http.get<Exchange[]>(URL).pipe(requestTimeout(), translateApiErrors());
  }

  create(name: string, type: ExchangeType): Observable<ExchangeCreation> {
    return this.http
      .post<ExchangeCreation>(URL, { name, type })
      .pipe(requestTimeout(), translateApiErrors());
  }

  /** El nombre va en la consulta porque puede contener caracteres como «/». */
  delete(name: string): Observable<void> {
    return this.http
      .delete<void>(`${URL}${queryString({ name })}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }
}
