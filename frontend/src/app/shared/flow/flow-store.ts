import { DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { Subject, Subscription, catchError, defer, of, retry, timer } from 'rxjs';
import { FLOW_SOURCE, FlowEvent, FlowMessage, FlowSourceStatus, FlowTick, FlowTopology } from './flow-model';
import { labelsOf } from './flow-logic';

/** Eventos que se conservan en pantalla, igual que en el servidor. */
export const MAX_EVENTS = 1_000;
/** Segundos de gráficas que se conservan. */
export const MAX_TICKS = 120;
/** Espera antes de volver a conectar cuando se corta el flujo. */
export const RECONNECT_MS = 2_000;

/** Un nodo está «activo» si ha pasado algún mensaje por él en este tiempo. */
export const ACTIVE_WINDOW_MS = 10_000;

/** Reloj del navegador; se sustituye en las pruebas. */
export const NOW = new InjectionToken<() => number>('Now', { providedIn: 'root', factory: () => () => Date.now() });

export type FlowConnection = 'connecting' | 'open' | 'lost';

/**
 * Estado del visualizador: lo que cuenta la fuente de datos, conservado aunque el flujo se corte. Se crea en la
 * pantalla que lo usa (no es global) y deja de escuchar con ella.
 */
@Injectable()
export class FlowStore {
  private readonly source = inject(FLOW_SOURCE);
  private readonly destroyRef = inject(DestroyRef);
  private readonly now = inject(NOW);
  private subscription?: Subscription;

  readonly status = signal<FlowSourceStatus | null>(null);
  readonly topology = signal<FlowTopology>({ nodes: [], edges: [] });
  /** El más reciente primero. */
  readonly events = signal<FlowEvent[]>([]);
  readonly ticks = signal<FlowTick[]>([]);
  readonly connection = signal<FlowConnection>('connecting');
  /** Eventos omitidos en el último segundo por superar el máximo que envía el servidor. */
  readonly omitted = computed(() => this.ticks().at(-1)?.omitted ?? 0);
  readonly labels = computed(() => labelsOf(this.topology()));
  /** Nodos con flujo de datos activo; el diagrama solo dibuja estos. */
  readonly active = signal<ReadonlySet<string>>(new Set());
  /** Cada evento nuevo, para quien lo anime. */
  readonly arrivals = new Subject<FlowEvent>();

  private newestId = 0;
  private readonly lastSeen = new Map<string, number>();

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.subscription?.unsubscribe();
      this.arrivals.complete();
    });
  }

  start(): void {
    if (this.subscription) {
      return;
    }
    this.subscription = new Subscription();
    this.subscription.add(
      this.source
        .recent()
        .pipe(catchError(() => of([] as FlowEvent[])))
        .subscribe((recent) => this.mergeRecent(recent)),
    );
    this.subscription.add(
      defer(() => this.source.stream())
        .pipe(
          // si se corta, se avisa y se vuelve a conectar sin perder lo que ya se mostraba
          retry({
            delay: () => {
              this.connection.set('lost');
              return timer(RECONNECT_MS);
            },
          }),
        )
        .subscribe((message) => this.receive(message)),
    );
  }

  /** Vacía el registro de eventos; las gráficas y el diagrama no cambian. */
  clearEvents(): void {
    this.events.set([]);
  }

  /** El historial llega por otra vía que el flujo: se une sin duplicar lo que el flujo ya haya entregado. */
  private mergeRecent(recent: FlowEvent[]): void {
    this.events.update((current) => {
      const known = new Set(current.map((event) => event.id));
      const merged = [...current, ...recent.filter((event) => !known.has(event.id))];
      return merged.sort((a, b) => b.id - a.id).slice(0, MAX_EVENTS);
    });
    this.newestId = Math.max(this.newestId, ...recent.map((event) => event.id));
  }

  private receive(message: FlowMessage): void {
    this.connection.set('open');
    switch (message.kind) {
      case 'status':
        this.status.set(message.status);
        break;
      case 'topology':
        this.topology.set(message.topology);
        break;
      case 'tick':
        this.addTick(message.tick);
        break;
      case 'event':
        this.addEvent(message.event);
        break;
    }
  }

  private addTick(tick: FlowTick): void {
    // al reconectar el servidor reenvía los ticks recientes: se ignoran los que ya se tienen
    const last = this.ticks().at(-1);
    if (last && tick.time <= last.time) {
      return;
    }
    this.ticks.update((current) => [...current, tick].slice(-MAX_TICKS));
    // al conectar el servidor reenvía ticks antiguos: solo cuentan los recientes
    if (this.now() - Date.parse(tick.time) <= ACTIVE_WINDOW_MS) {
      for (const rates of [tick.published, tick.delivered]) {
        Object.entries(rates)
          .filter(([, rate]) => rate > 0)
          .forEach(([id]) => this.touch(id));
      }
    }
    this.refreshActive();
  }

  private addEvent(event: FlowEvent): void {
    if (event.id <= this.newestId) {
      return;
    }
    this.newestId = event.id;
    this.events.update((current) => [event, ...current].slice(0, MAX_EVENTS));
    [event.producer, event.router, ...event.destinations, event.consumer].forEach((id) => this.touch(id));
    this.refreshActive();
    this.arrivals.next(event);
  }

  private touch(id: string | null | undefined): void {
    if (id) {
      this.lastSeen.set(id, this.now());
    }
  }

  /** Olvida los nodos sin actividad reciente y publica los que siguen activos. */
  private refreshActive(): void {
    const limit = this.now() - ACTIVE_WINDOW_MS;
    for (const [id, seenAt] of this.lastSeen) {
      if (seenAt < limit) {
        this.lastSeen.delete(id);
      }
    }
    const ids = new Set(this.lastSeen.keys());
    const current = this.active();
    if (ids.size !== current.size || [...ids].some((id) => !current.has(id))) {
      this.active.set(ids);
    }
  }
}
