import { InjectionToken } from '@angular/core';

/** Lo que el servicio necesita de un `EventSource`; permite sustituirlo por uno falso en las pruebas. */
export interface EventSourceLike {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  /** Se invoca ante cualquier fallo de la conexión. */
  onerror: ((event: Event) => void) | null;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

/** Crea el `EventSource` de la sesión. */
export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url),
});
