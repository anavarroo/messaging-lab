import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { FlowNode, FlowTick, FlowTopology } from '../flow-model';
import { ChartSeries, LineChart } from '../line-chart/line-chart';

/** Series que se dibujan sin que el usuario elija. */
export const DEFAULT_SERIES = 4;

/** Las dos gráficas del visualizador: mensajes por segundo y mensajes listos, de los últimos 2 minutos. */
@Component({
  selector: 'app-flow-charts',
  imports: [LineChart],
  templateUrl: './flow-charts.html',
  styleUrls: ['../../lab-area.css', './flow-charts.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowCharts {
  readonly ticks = input.required<readonly FlowTick[]>();
  readonly topology = input.required<FlowTopology>();

  /** `null`: se eligen solas las de más actividad; si el usuario marca alguna, la elección es suya. */
  private readonly chosen = signal<ReadonlySet<string> | null>(null);

  /** Enrutadores y destinos: lo que tiene sentido medir. */
  protected readonly candidates = computed<FlowNode[]>(() =>
    this.topology()
      .nodes.filter((node) => (node.kind === 'ROUTER' || node.kind === 'DESTINATION') && this.activity(node) > 0)
      .sort((a, b) => a.label.localeCompare(b.label)),
  );

  protected readonly selected = computed<ReadonlySet<string>>(() => {
    const chosen = this.chosen();
    if (chosen) {
      return chosen;
    }
    const ranked = [...this.candidates()].sort((a, b) => this.activity(b) - this.activity(a));
    return new Set(ranked.slice(0, DEFAULT_SERIES).map((node) => node.id));
  });

  protected readonly selectedNodes = computed(() => this.candidates().filter((node) => this.selected().has(node.id)));

  protected readonly rates = computed<ChartSeries[]>(() =>
    this.selectedNodes().flatMap((node) => [
      { name: `${node.label} · publicados`, values: this.ticks().map((tick) => tick.published[node.id] ?? 0) },
      { name: `${node.label} · entregados`, values: this.ticks().map((tick) => tick.delivered[node.id] ?? 0) },
    ]),
  );

  protected readonly ready = computed<ChartSeries[]>(() =>
    this.selectedNodes()
      .filter((node) => node.kind === 'DESTINATION')
      .map((node) => ({ name: node.label, values: this.ticks().map((tick) => tick.ready[node.id] ?? 0) })),
  );

  /** Lo que ha pasado por un nodo en las gráficas; los nodos sin nada no se ofrecen para medir. */
  private activity(node: FlowNode): number {
    return this.ticks().reduce(
      (sum, tick) => sum + (tick.published[node.id] ?? 0) + (tick.delivered[node.id] ?? 0) + (tick.ready[node.id] ?? 0),
      0,
    );
  }

  protected toggle(node: FlowNode, checked: boolean): void {
    const next = new Set(this.selected());
    if (checked) {
      next.add(node.id);
    } else {
      next.delete(node.id);
    }
    this.chosen.set(next);
  }
}
