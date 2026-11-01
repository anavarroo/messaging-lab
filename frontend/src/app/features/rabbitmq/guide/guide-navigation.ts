import { Injectable, signal } from '@angular/core';

export type GuideView = 'explicacion' | 'pizzeria' | 'variante' | 'generador';

export interface GuideViewInfo {
  id: GuideView;
  label: string;
  description: string;
}

/** Las partes de la guía, en el orden del submenú. */
export const GUIDE_VIEWS: readonly GuideViewInfo[] = [
  { id: 'explicacion', label: 'Cómo funciona', description: 'Qué es cada pieza de RabbitMQ' },
  { id: 'pizzeria', label: 'Ejemplo 1: la pizzería', description: 'Paso a paso, a mano' },
  { id: 'variante', label: 'Ejemplo 2: la variante', description: 'Un aviso para todos con fanout' },
  { id: 'generador', label: 'Ejemplo 3: con el generador', description: 'Mensajes continuos, vistos en «Flujo»' },
];

/**
 * Qué parte de la guía se está leyendo. Vive fuera de la pantalla para que, al ir a otra pestaña del laboratorio y
 * volver, se vea la misma parte donde se estaba.
 */
@Injectable({ providedIn: 'root' })
export class GuideNavigation {
  readonly view = signal<GuideView>('explicacion');
}
