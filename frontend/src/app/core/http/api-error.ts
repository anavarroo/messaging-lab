import { HttpErrorResponse } from '@angular/common/http';
import { MonoTypeOperatorFunction, TimeoutError, catchError, throwError } from 'rxjs';

/**
 * Tipo de fallo de una operación:
 * - `backend-unavailable`: no se ha podido contactar con el backend (sin respuesta, red o proxy).
 * - `broker-unavailable`: el backend responde, pero no puede comunicarse con RabbitMQ.
 * - `timeout`: la operación ha superado el tiempo máximo de espera.
 * - `validation`, `not-found`, `conflict`, `broker-rejected`: el backend ha rechazado la operación.
 * - `unknown`: cualquier otro fallo.
 */
export type ApiErrorKind =
  | 'backend-unavailable'
  | 'broker-unavailable'
  | 'timeout'
  | 'validation'
  | 'not-found'
  | 'conflict'
  | 'broker-rejected'
  | 'unknown';

/** Error del backend tal como lo define el contrato (`ApiError`). */
interface ApiErrorBody {
  code: string;
  message: string;
}

/** Fallo de una operación contra el backend, con un mensaje en español dirigido al usuario. */
export class ApiError extends Error {
  constructor(
    readonly kind: ApiErrorKind,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const BACKEND_UNAVAILABLE_MESSAGE = 'El backend no está disponible.';
export const TIMEOUT_MESSAGE =
  'La operación ha superado el tiempo máximo de espera. Inténtalo de nuevo.';
export const UNKNOWN_MESSAGE = 'No se ha podido completar la operación.';

const KIND_BY_CODE: Record<string, ApiErrorKind> = {
  VALIDATION: 'validation',
  NOT_FOUND: 'not-found',
  CONFLICT: 'conflict',
  BROKER_UNAVAILABLE: 'broker-unavailable',
  TIMEOUT: 'timeout',
  BROKER_REJECTED: 'broker-rejected',
};

/** Códigos con los que un proxy indica que no ha podido alcanzar el backend. */
const GATEWAY_ERROR_STATUSES = [502, 503, 504];

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['code'] === 'string' &&
    candidate['code'] in KIND_BY_CODE &&
    typeof candidate['message'] === 'string' &&
    candidate['message'] !== ''
  );
}

/**
 * Traduce cualquier fallo de una petición a un {@link ApiError} con un mensaje en español, sin trazas ni
 * detalles técnicos (CA-11.3). Distingue que el backend no responda (red o proxy) de que sea el propio
 * backend quien informa de un problema, que llega como `ApiError` en el cuerpo.
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  if (error instanceof TimeoutError) {
    return new ApiError('timeout', TIMEOUT_MESSAGE);
  }
  if (error instanceof HttpErrorResponse) {
    if (isApiErrorBody(error.error)) {
      return new ApiError(KIND_BY_CODE[error.error.code], error.error.message);
    }
    if (error.status === 0 || GATEWAY_ERROR_STATUSES.includes(error.status)) {
      return new ApiError('backend-unavailable', BACKEND_UNAVAILABLE_MESSAGE);
    }
  }
  return new ApiError('unknown', UNKNOWN_MESSAGE);
}

/** Operador que traduce los fallos de la petición a {@link ApiError}. */
export function translateApiErrors<T>(): MonoTypeOperatorFunction<T> {
  return catchError((error: unknown) => throwError(() => toApiError(error)));
}
