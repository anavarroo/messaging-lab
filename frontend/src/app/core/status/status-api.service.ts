import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, TimeoutError, catchError, map, throwError, timeout } from 'rxjs';
import { REQUEST_TIMEOUT_MS } from '../http/request-timeout';
import { BackendStatus, isBackendStatus } from './backend-status';

/** Tiempo máximo de espera de la respuesta del backend (RNF-4), compartido con el resto de peticiones. */
export const STATUS_TIMEOUT_MS = REQUEST_TIMEOUT_MS;

/**
 * - `unavailable`: no se ha podido contactar con el backend (sin respuesta, red o proxy).
 * - `invalid`: el backend ha respondido, pero con un error o con una respuesta no válida.
 */
export type StatusErrorKind = 'unavailable' | 'invalid';

const STATUS_ERROR_MESSAGES: Record<StatusErrorKind, string> = {
  unavailable: 'El backend no está disponible.',
  invalid: 'No se ha podido obtener el estado del backend.',
};

/** Error de la consulta de estado, con el mensaje en español dirigido al usuario. */
export class StatusError extends Error {
  constructor(readonly kind: StatusErrorKind) {
    super(STATUS_ERROR_MESSAGES[kind]);
    this.name = 'StatusError';
  }
}

/** Códigos con los que un proxy indica que no ha podido alcanzar el backend. */
const GATEWAY_ERROR_STATUSES = [502, 503, 504];

function toStatusError(error: unknown): StatusError {
  if (error instanceof StatusError) {
    return error;
  }
  if (error instanceof TimeoutError) {
    return new StatusError('unavailable');
  }
  if (
    error instanceof HttpErrorResponse &&
    (error.status === 0 || GATEWAY_ERROR_STATUSES.includes(error.status))
  ) {
    return new StatusError('unavailable');
  }
  return new StatusError('invalid');
}

@Injectable({ providedIn: 'root' })
export class StatusApiService {
  private readonly http = inject(HttpClient);

  /** Consulta el estado del backend; los fallos llegan como {@link StatusError}. */
  getStatus(): Observable<BackendStatus> {
    return this.http.get<unknown>('/api/status').pipe(
      timeout(STATUS_TIMEOUT_MS),
      map((body) => {
        if (!isBackendStatus(body)) {
          throw new StatusError('invalid');
        }
        return body;
      }),
      catchError((error) => throwError(() => toStatusError(error))),
    );
  }
}
