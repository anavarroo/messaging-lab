import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  InjectionToken,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FlowAnimator, Particle } from '../flow-animator';
import { FlowCharts } from '../flow-charts/flow-charts';
import { FlowDiagram } from '../flow-diagram/flow-diagram';
import { FlowLog } from '../flow-log/flow-log';
import { averageRates, hopsOf, matchesFilter } from '../flow-logic';
import { DEFAULT_VOCABULARY, FlowEvent, FlowTopology, FlowVocabulary } from '../flow-model';
import { FlowStore } from '../flow-store';

/** Fotogramas de la animación; se sustituye en las pruebas para avanzarlos a mano. */
export interface FrameScheduler {
  request(callback: (now: number) => void): number;
  cancel(id: number): void;
}

export const ANIMATION_FRAME = new InjectionToken<FrameScheduler>('AnimationFrame', {
  providedIn: 'root',
  factory: () => ({
    request: (callback) => requestAnimationFrame(callback),
    cancel: (id) => cancelAnimationFrame(id),
  }),
});

/** ¿Pide el usuario que no haya movimiento en las animaciones? */
export const PREFERS_REDUCED_MOTION = new InjectionToken<() => boolean>('PrefersReducedMotion', {
  providedIn: 'root',
  factory: () => () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches,
});

/** Un fotograma que tarda más que esto (pestaña en segundo plano) no hace saltar la animación. */
const MAX_FRAME_MS = 100;

export const SPEEDS = [
  { value: 1, label: 'Normal' },
  { value: 0.5, label: 'Lenta (÷2)' },
  { value: 0.25, label: 'Más lenta (÷4)' },
  { value: 0.1, label: 'Cámara lenta (÷10)' },
] as const;

/**
 * Visualizador de flujo completo: estado de la fuente, diagrama animado, registro y gráficas. No sabe de qué sistema
 * de mensajería vienen los datos: cada laboratorio lo coloca en su pestaña y aporta su {@link FlowSource} con el token
 * `FLOW_SOURCE`.
 */
@Component({
  selector: 'app-flow-view',
  imports: [FlowDiagram, FlowLog, FlowCharts],
  providers: [FlowStore],
  templateUrl: './flow-view.html',
  styleUrls: ['../../lab-area.css', './flow-view.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowView implements OnInit {
  protected readonly store = inject(FlowStore);
  private readonly frames = inject(ANIMATION_FRAME);
  private readonly animator = new FlowAnimator();

  readonly vocabulary = input<FlowVocabulary>(DEFAULT_VOCABULARY);

  protected readonly speeds = SPEEDS;
  protected readonly filter = signal('');
  protected readonly animationPaused = signal(false);
  protected readonly speed = signal<number>(1);
  protected readonly particles = signal<Particle[]>([]);
  protected readonly reducedMotion = inject(PREFERS_REDUCED_MOTION)();

  protected readonly visibleEvents = computed(() => {
    const filter = this.filter();
    const labels = this.store.labels();
    return filter.trim() ? this.store.events().filter((event) => matchesFilter(event, filter, labels)) : this.store.events();
  });

  /** Solo los nodos con flujo activo y las conexiones entre ellos. */
  protected readonly activeTopology = computed<FlowTopology>(() => {
    const active = this.store.active();
    const { nodes, edges } = this.store.topology();
    return {
      nodes: nodes.filter((node) => active.has(node.id)),
      edges: edges.filter((edge) => active.has(edge.from) && active.has(edge.to)),
    };
  });

  protected readonly lastTick = computed(() => this.store.ticks().at(-1));
  /** Ritmo medio de los últimos segundos: un emisor lento no debe verse parado entre mensajes. */
  protected readonly publishedRates = computed(() => averageRates(this.store.ticks(), (tick) => tick.published));
  protected readonly deliveredRates = computed(() => averageRates(this.store.ticks(), (tick) => tick.delivered));

  private frame: number | null = null;
  private lastFrameAt = 0;

  constructor() {
    this.animator.reducedMotion = this.reducedMotion;
    inject(DestroyRef).onDestroy(() => this.stopLoop());
  }

  ngOnInit(): void {
    this.store.arrivals.subscribe((event) => this.animate(event));
    this.store.start();
  }

  protected setFilter(value: string): void {
    this.filter.set(value);
  }

  protected togglePause(): void {
    this.animationPaused.update((paused) => !paused);
    this.animator.paused = this.animationPaused();
    if (this.animator.active) {
      this.startLoop();
    }
  }

  protected setSpeed(value: string): void {
    this.speed.set(Number(value));
    this.animator.speed = this.speed();
  }

  private animate(event: FlowEvent): void {
    const labels = this.store.labels();
    if (!matchesFilter(event, this.filter(), labels)) {
      return;
    }
    const known = new Set(this.store.topology().nodes.map((node) => node.id));
    if (this.animator.add(hopsOf(event, known), event.unrouted)) {
      this.startLoop();
    }
  }

  private startLoop(): void {
    if (this.frame === null) {
      this.lastFrameAt = 0;
      this.frame = this.frames.request((now) => this.step(now));
    }
  }

  private step(now: number): void {
    const elapsed = this.lastFrameAt ? Math.min(now - this.lastFrameAt, MAX_FRAME_MS) : 0;
    this.lastFrameAt = now;
    this.animator.advance(elapsed);
    this.particles.set(this.animator.particles);
    // sin nada que mover, se deja de pedir fotogramas
    this.frame = this.animator.active ? this.frames.request((next) => this.step(next)) : null;
  }

  private stopLoop(): void {
    if (this.frame !== null) {
      this.frames.cancel(this.frame);
      this.frame = null;
    }
  }
}
