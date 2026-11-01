import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { DEFAULT_VOCABULARY, FlowEvent, FlowVocabulary } from '../flow-model';

/** Filas que se dibujan como máximo; el resto sigue en memoria pero no pesa en la pantalla. */
export const VISIBLE_ROWS = 200;

/** Registro de los últimos eventos, con pausa, limpiar y el detalle de cada uno. */
@Component({
  selector: 'app-flow-log',
  templateUrl: './flow-log.html',
  styleUrls: ['../../lab-area.css', './flow-log.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowLog {
  /** El más reciente primero. */
  readonly events = input.required<readonly FlowEvent[]>();
  readonly labels = input<ReadonlyMap<string, string>>(new Map());
  readonly vocabulary = input<FlowVocabulary>(DEFAULT_VOCABULARY);
  /** Eventos que el servidor no envía cada segundo por superar el máximo. */
  readonly omitted = input(0);
  readonly cleared = output<void>();

  protected readonly paused = signal(false);
  private readonly frozen = signal<readonly FlowEvent[]>([]);
  protected readonly selectedId = signal<number | null>(null);

  protected readonly shown = computed(() => (this.paused() ? this.frozen() : this.events()));
  protected readonly rows = computed(() => this.shown().slice(0, VISIBLE_ROWS));
  protected readonly selected = computed(() => this.shown().find((event) => event.id === this.selectedId()) ?? null);
  protected readonly hiddenRows = computed(() => Math.max(this.shown().length - VISIBLE_ROWS, 0));
  /** Eventos que han llegado mientras estaba en pausa. */
  protected readonly waiting = computed(() => (this.paused() ? this.events().length - this.frozen().length : 0));

  protected togglePause(): void {
    if (!this.paused()) {
      this.frozen.set(this.events());
    }
    this.paused.update((paused) => !paused);
  }

  protected clear(): void {
    this.frozen.set([]);
    this.selectedId.set(null);
    this.cleared.emit();
  }

  protected select(event: FlowEvent): void {
    this.selectedId.update((current) => (current === event.id ? null : event.id));
  }

  protected name(id: string | null | undefined): string {
    return id ? (this.labels().get(id) ?? id) : '';
  }

  /** Recorrido de un evento: de dónde a dónde. */
  protected route(event: FlowEvent): string {
    if (event.type === 'PUBLISH') {
      const where = event.destinations.map((id) => this.name(id)).join(', ');
      return `${this.name(event.router)} → ${where || 'sin destino'}`;
    }
    return `${event.destinations.map((id) => this.name(id)).join(', ')} → ${this.name(event.consumer) || '—'}`;
  }

  protected time(iso: string): string {
    const date = new Date(iso);
    const pad = (value: number, size = 2) => String(value).padStart(size, '0');
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
  }

  protected properties(event: FlowEvent): string {
    return JSON.stringify(event.properties ?? {}, null, 2);
  }
}
