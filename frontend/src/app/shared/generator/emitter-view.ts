/** Estado de un emisor simulado. Es común a cualquier sistema de mensajería. */
export type EmitterStatus = 'STOPPED' | 'RUNNING' | 'PAUSED' | 'FINISHED' | 'FAILED';

/** Acciones sobre un emisor. */
export type EmitterAction = 'START' | 'PAUSE' | 'RESUME' | 'STOP';

/** Cada cuánto ocurre algo: fijo o aleatorio dentro de un rango, en milisegundos. */
export interface Interval {
  mode: 'FIXED' | 'RANGE';
  fixedMs?: number | null;
  minMs?: number | null;
  maxMs?: number | null;
}

/**
 * Lo que la lista de emisores necesita de un emisor, sin saber a qué sistema de mensajería publica: el destino
 * lo describe quien usa la lista.
 */
export interface EmitterView {
  id: string;
  name: string;
  interval: Interval;
  status: EmitterStatus;
  published: number;
  unrouted: number;
  ratePerSecond: number;
  stopMessage?: string | null;
}

export const EMITTER_STATUS_LABELS: Record<EmitterStatus, string> = {
  STOPPED: 'Parado',
  RUNNING: 'En marcha',
  PAUSED: 'En pausa',
  FINISHED: 'Terminado',
  FAILED: 'Fallado',
};

function duration(ms: number): string {
  if (ms >= 1000 && ms % 1000 === 0) {
    return `${ms / 1000} s`;
  }
  return `${ms} ms`;
}

/** Texto de una frecuencia: «cada 500 ms», «cada 2 s» o «entre 200 ms y 800 ms». */
export function describeInterval(interval: Interval): string {
  if (interval.mode === 'FIXED') {
    return `cada ${duration(interval.fixedMs ?? 0)}`;
  }
  return `entre ${duration(interval.minMs ?? 0)} y ${duration(interval.maxMs ?? 0)}`;
}

/** Mensajes por segundo en el mejor caso de una frecuencia, para mostrarlo junto a ella. */
export function maxPerSecond(interval: Interval): number {
  const shortest = interval.mode === 'FIXED' ? (interval.fixedMs ?? 0) : (interval.minMs ?? 0);
  return shortest > 0 ? 1000 / shortest : 0;
}

/** Plantilla predefinida para empezar rápido; sus valores se pueden ajustar. */
export interface TemplatePreset {
  id: string;
  name: string;
  description: string;
  template: string;
  suggestedInterval: Interval;
  /** Clave de ejemplo del destino (en RabbitMQ, una routing key con la forma de un topic de MQTT). */
  suggestedRoutingKey: string;
  onlyWhenChanged: boolean;
}

/** Ejemplo de un cuerpo generado con una plantilla. */
export interface PreviewResult {
  body: string;
  bytes: number;
}

/** Lo que el usuario define de un emisor, salvo su destino, que depende del sistema de mensajería. */
export interface EmitterFormValue {
  name: string;
  interval: Interval;
  template: string;
  maxMessages: number | null;
  maxDurationSeconds: number | null;
  onlyWhenChanged: boolean;
}
