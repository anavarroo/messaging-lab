import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { describeInterval } from '../../../shared/generator/emitter-view';
import { toInterval, validateInterval } from '../../../shared/generator/interval-validation';
import { Queue } from '../queues/queues-api';
import { SimulatedConsumer, SimulatedConsumerRequest } from './generator-api';

export interface ConsumerActionRequest {
  consumer: SimulatedConsumer;
  action: 'START' | 'STOP';
}

const STATUS_LABELS = { STOPPED: 'Parado', RUNNING: 'En marcha', FAILED: 'Fallado' } as const;

/** Alta, estado y baja de los consumidores simulados, que consumen de una cola con un tiempo de proceso y fallos. */
@Component({
  selector: 'app-simulated-consumers',
  imports: [ReactiveFormsModule],
  templateUrl: './simulated-consumers.html',
  styleUrls: ['../shared/lab-area.css', './simulated-consumers.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SimulatedConsumers {
  readonly consumers = input<readonly SimulatedConsumer[] | null>(null);
  readonly queues = input<readonly Queue[]>([]);
  readonly busyIds = input<ReadonlySet<string>>(new Set());
  readonly saving = input(false);
  readonly submitError = input<string | null>(null);

  readonly created = output<SimulatedConsumerRequest>();
  readonly requested = output<ConsumerActionRequest>();
  readonly removed = output<SimulatedConsumer>();

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly intervalText = describeInterval;
  protected readonly attempted = signal(false);
  private readonly changes = signal(0);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    queue: new FormControl('', { nonNullable: true }),
    mode: new FormControl<'FIXED' | 'RANGE'>('FIXED', { nonNullable: true }),
    fixedMs: new FormControl<number | null>(100),
    minMs: new FormControl<number | null>(null),
    maxMs: new FormControl<number | null>(null),
    failurePercent: new FormControl<number | null>(0),
    onFailure: new FormControl<'REQUEUE' | 'REJECT'>('REQUEUE', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.changes.update((n) => n + 1));
  }

  protected errors() {
    this.changes();
    const v = this.form.getRawValue();
    const percent = v.failurePercent;
    return {
      name: v.name.trim() ? null : 'Escribe un nombre para el consumidor.',
      queue: v.queue ? null : 'Elige la cola de la que consumir.',
      processing: validateInterval(v.mode, v.fixedMs, v.minMs, v.maxMs, 'el tiempo de proceso'),
      failure:
        percent !== null && Number.isInteger(percent) && percent >= 0 && percent <= 100
          ? null
          : 'El porcentaje de fallos debe ser un número entero entre 0 y 100.',
    };
  }

  protected isBusy(consumer: SimulatedConsumer): boolean {
    return this.busyIds().has(consumer.id);
  }

  protected rate(consumer: SimulatedConsumer): string {
    const value = consumer.ratePerSecond;
    return Number.isInteger(value) ? String(value) : value.toFixed(1).replace('.', ',');
  }

  protected submit(): void {
    this.attempted.set(true);
    if (this.saving() || Object.values(this.errors()).some((message) => message !== null)) {
      return;
    }
    const v = this.form.getRawValue();
    this.created.emit({
      name: v.name.trim(),
      queue: v.queue,
      processing: toInterval(v.mode, v.fixedMs, v.minMs, v.maxMs),
      failurePercent: v.failurePercent as number,
      onFailure: v.onFailure,
    });
  }

  reset(): void {
    this.form.reset({ mode: 'FIXED', fixedMs: 100, failurePercent: 0, onFailure: 'REQUEUE' });
    this.attempted.set(false);
  }
}
