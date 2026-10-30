import { Interval } from './emitter-view';

/** Intervalo mínimo y máximo admitidos, en milisegundos (los mismos que valida el servidor). */
export const MIN_INTERVAL_MS = 2;
export const MAX_INTERVAL_MS = 24 * 60 * 60 * 1000;

/**
 * Valida un intervalo con los mismos criterios y mensajes que el servidor, para responder al momento.
 * @param subject de qué es el intervalo, como aparece en el mensaje: «la frecuencia», «el tiempo de proceso»
 * @returns el motivo del rechazo, o `null` si es válido
 */
export function validateInterval(
  mode: Interval['mode'],
  fixedMs: number | null,
  minMs: number | null,
  maxMs: number | null,
  subject: string,
): string | null {
  const whole = (value: number | null): value is number => value !== null && Number.isInteger(value);
  if (mode === 'FIXED') {
    if (!whole(fixedMs)) {
      return `Indica el valor fijo de ${subject}, en milisegundos.`;
    }
    return range(fixedMs, fixedMs);
  }
  if (!whole(minMs) || !whole(maxMs)) {
    return `Indica el mínimo y el máximo del rango de ${subject}, en milisegundos.`;
  }
  return range(minMs, maxMs);
}

function range(low: number, high: number): string | null {
  if (low < MIN_INTERVAL_MS || high > MAX_INTERVAL_MS) {
    return `Los intervalos deben estar entre ${MIN_INTERVAL_MS} ms y ${MAX_INTERVAL_MS} ms (un día).`;
  }
  if (low > high) {
    return `El mínimo del rango (${low} ms) no puede ser mayor que el máximo (${high} ms).`;
  }
  return null;
}

/** Construye el intervalo a partir de los campos de un formulario. */
export function toInterval(mode: Interval['mode'], fixedMs: number | null, minMs: number | null, maxMs: number | null): Interval {
  return mode === 'FIXED' ? { mode, fixedMs } : { mode, minMs, maxMs };
}
