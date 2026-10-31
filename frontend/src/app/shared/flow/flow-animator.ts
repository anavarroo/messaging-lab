import { Hop } from './flow-logic';

/** Un punto que recorre un tramo del diagrama. */
export interface Particle {
  key: number;
  from: string;
  to: string;
  /** De 0 (sale del nodo origen) a 1 (llega al destino). */
  progress: number;
  /** Publicación que no llegó a ninguna cola: se dibuja distinta, sin depender solo del color. */
  unrouted: boolean;
}

interface Travel {
  key: number;
  from: string;
  to: string;
  start: number;
  unrouted: boolean;
}

/** Duración de cada tramo a velocidad normal, en milisegundos. */
export const HOP_MS = 700;
/** Máximo de puntos a la vez; si se supera, se descartan los más antiguos. */
export const MAX_PARTICLES = 250;

/**
 * Mueve los puntos del diagrama. Tiene su propio reloj, que solo avanza con {@link advance}, así que la pausa y la
 * cámara lenta consisten en no avanzarlo o avanzarlo más despacio, y se prueba sin temporizadores.
 */
export class FlowAnimator {
  /** 1 = tiempo real; 0,1 = diez veces más despacio. */
  speed = 1;
  paused = false;
  /** Con movimiento reducido no hay animación: los eventos solo se ven en el registro y en los contadores. */
  reducedMotion = false;

  private time = 0;
  private sequence = 0;
  private travels: Travel[] = [];

  /** Anima un evento. No hace nada en pausa ni con movimiento reducido; devuelve si lo ha animado. */
  add(hops: Hop[], unrouted: boolean): boolean {
    if (this.paused || this.reducedMotion || hops.length === 0) {
      return false;
    }
    for (const hop of hops) {
      this.travels.push({
        key: this.sequence++,
        from: hop.from,
        to: hop.to,
        start: this.time + hop.stage * HOP_MS,
        unrouted,
      });
    }
    if (this.travels.length > MAX_PARTICLES) {
      this.travels = this.travels.slice(-MAX_PARTICLES);
    }
    return true;
  }

  /** Avanza el reloj `elapsedMs` de tiempo real, multiplicado por la velocidad; en pausa no avanza. */
  advance(elapsedMs: number): void {
    if (this.paused) {
      return;
    }
    this.time += elapsedMs * this.speed;
    this.travels = this.travels.filter((travel) => this.time - travel.start < HOP_MS);
  }

  /** Los puntos que se ven ahora (los que todavía esperan su turno no). */
  get particles(): Particle[] {
    return this.travels
      .filter((travel) => this.time >= travel.start)
      .map((travel) => ({
        key: travel.key,
        from: travel.from,
        to: travel.to,
        progress: (this.time - travel.start) / HOP_MS,
        unrouted: travel.unrouted,
      }));
  }

  /** ¿Hay algo en movimiento o esperando? Si no, no hace falta seguir dibujando fotogramas. */
  get active(): boolean {
    return !this.paused && this.travels.length > 0;
  }
}
