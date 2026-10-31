import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface ChartSeries {
  name: string;
  /** Un valor por segundo, del más antiguo al más reciente. */
  values: readonly number[];
}

const WIDTH = 640;
const HEIGHT = 200;
const LEFT = 48;
const RIGHT = 12;
const TOP = 22;
const BOTTOM = 24;

/** Trazos distintos, para que las series se distingan también sin depender del color. */
const DASHES = ['', '6 3', '2 3', '10 3 2 3', '1 4'];

interface DrawnSeries {
  name: string;
  index: number;
  dash: string;
  points: string;
  last: number;
  max: number;
  average: number;
}

/** Gráfica de líneas en SVG, con leyenda, ejes con unidades y una tabla que resume los mismos datos. */
@Component({
  selector: 'app-line-chart',
  templateUrl: './line-chart.html',
  styleUrl: './line-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LineChart {
  readonly title = input.required<string>();
  /** Unidad del eje vertical, por ejemplo «mensajes/s». */
  readonly unit = input.required<string>();
  readonly series = input<readonly ChartSeries[]>([]);
  /** Segundos que abarca el eje horizontal. */
  readonly window = input(120);

  protected readonly box = { width: WIDTH, height: HEIGHT, left: LEFT, right: WIDTH - RIGHT, top: TOP, bottom: HEIGHT - BOTTOM };

  /** Máximo del eje vertical, redondeado a un valor «bonito» (1, 2, 5 por una potencia de diez). */
  protected readonly top = computed(() => {
    const highest = Math.max(0, ...this.series().flatMap((series) => series.values));
    if (highest <= 0) {
      return 1;
    }
    const magnitude = 10 ** Math.floor(Math.log10(highest));
    return ([1, 2, 5, 10].map((step) => step * magnitude).find((value) => value >= highest) as number) ?? highest;
  });

  protected readonly drawn = computed<DrawnSeries[]>(() => {
    const seconds = this.window();
    const top = this.top();
    return this.series().map((series, index) => {
      const values = series.values.slice(-seconds);
      const offset = seconds - values.length;
      const points = values
        .map((value, i) => {
          const x = LEFT + ((offset + i) / (seconds - 1)) * (WIDTH - RIGHT - LEFT);
          const y = HEIGHT - BOTTOM - (value / top) * (HEIGHT - BOTTOM - TOP);
          return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(' ');
      const total = values.reduce((sum, value) => sum + value, 0);
      return {
        name: series.name,
        index,
        dash: DASHES[index % DASHES.length],
        points,
        last: values.at(-1) ?? 0,
        max: Math.max(0, ...values),
        average: values.length ? total / values.length : 0,
      };
    });
  });

  protected readonly empty = computed(() => this.series().every((series) => series.values.length === 0));

  protected format(value: number): string {
    if (Number.isInteger(value)) {
      return String(value);
    }
    return value.toFixed(value < 10 ? 1 : 0).replace('.', ',');
  }

  protected minutes(): string {
    const minutes = this.window() / 60;
    return Number.isInteger(minutes) ? `${minutes} min` : `${this.window()} s`;
  }
}
