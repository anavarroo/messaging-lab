import { MonoTypeOperatorFunction, timeout } from 'rxjs';

/** Tiempo máximo de espera de la respuesta del backend (RNF-4). */
export const REQUEST_TIMEOUT_MS = 20_000;

/** Falla con `TimeoutError` si la respuesta tarda más del tiempo máximo compartido. */
export function requestTimeout<T>(): MonoTypeOperatorFunction<T> {
  return timeout(REQUEST_TIMEOUT_MS);
}
