import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangeType, ExchangesApi } from '../exchanges/exchanges-api';
import { Queue, QueuesApi } from '../queues/queues-api';
import { validateRoutingKey, validateTopicPattern } from '../validation/validation';
import { Binding, BindingsApi } from './bindings-api';

const TYPE_LABELS: Record<ExchangeType, string> = { DIRECT: 'Direct', FANOUT: 'Fanout', TOPIC: 'Topic' };

/** Área de bindings: listado filtrable, creación y borrado (RF-6). */
@Component({
  selector: 'app-bindings-page',
  imports: [ReactiveFormsModule],
  templateUrl: './bindings-page.html',
  styleUrls: ['../shared/lab-area.css', './bindings-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BindingsPage implements OnInit {
  private readonly api = inject(BindingsApi);
  private readonly exchangesApi = inject(ExchangesApi);
  private readonly queuesApi = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);
  private readonly builder = inject(NonNullableFormBuilder);

  protected readonly form = this.builder.group({
    exchange: ['', Validators.required],
    queue: ['', Validators.required],
    routingKey: [''],
  });

  protected readonly filters = this.builder.group({ exchange: [''], queue: [''] });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly typeLabels = TYPE_LABELS;

  /** El exchange por defecto no admite bindings (CA-6.9): no se ofrece. */
  protected readonly exchanges = signal<Exchange[] | null>(null);
  protected readonly bindableExchanges = computed(() =>
    (this.exchanges() ?? []).filter((exchange) => exchange.name !== ''),
  );
  protected readonly queues = signal<Queue[] | null>(null);
  protected readonly bindings = signal<Binding[] | null>(null);

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly submitted = signal(false);

  protected readonly selectedType = computed<ExchangeType | null>(() => {
    const name = this.formValue().exchange;
    return this.bindableExchanges().find((exchange) => exchange.name === name)?.type ?? null;
  });

  /** Motivo por el que la routing key o el patrón escritos no son válidos, según el tipo del exchange. */
  protected readonly routingKeyError = computed(() => {
    const key = this.formValue().routingKey ?? '';
    switch (this.selectedType()) {
      case 'TOPIC':
        return validateTopicPattern(key);
      case 'DIRECT':
        return validateRoutingKey(key);
      default:
        return null;
    }
  });

  ngOnInit(): void {
    this.loadChoices();
    this.reload();
    // cambiar un filtro vuelve a pedir el listado ya filtrado
    this.filters.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.reload());
  }

  protected exchangeError(): string | null {
    return this.showErrors() && this.form.controls.exchange.invalid ? 'Elige un exchange.' : null;
  }

  protected queueError(): string | null {
    return this.showErrors() && this.form.controls.queue.invalid ? 'Elige una cola.' : null;
  }

  protected showRoutingKeyError(): boolean {
    return this.submitted() || this.form.controls.routingKey.touched;
  }

  private showErrors(): boolean {
    return this.submitted();
  }

  /** Carga los exchanges y las colas que se ofrecen en el formulario y en los filtros. */
  protected loadChoices(): void {
    this.exchangesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (exchanges) => this.exchanges.set(exchanges),
        error: (error: ApiError) => this.failLoading(error),
      });
    this.queuesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (queues) => this.queues.set(queues),
        error: (error: ApiError) => this.failLoading(error),
      });
  }

  /** Recarga el listado aplicando los filtros elegidos. */
  protected reload(): void {
    const { exchange, queue } = this.filters.getRawValue();
    this.loading.set(true);
    this.api
      .list({ exchange: exchange || null, queue: queue || null })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bindings) => {
          this.bindings.set(bindings);
          this.loadError.set(null);
          this.loading.set(false);
        },
        error: (error: ApiError) => {
          this.failLoading(error);
          this.loading.set(false);
        },
      });
  }

  protected displayKey(binding: Binding): string {
    return binding.routingKey === '' ? '(vacía)' : binding.routingKey;
  }

  protected create(): void {
    this.submitted.set(true);
    this.form.controls.routingKey.markAsTouched();
    if (this.busy() || this.form.invalid || this.routingKeyError()) {
      return;
    }
    const { exchange, queue, routingKey } = this.form.getRawValue();
    const key = this.selectedType() === 'FANOUT' ? '' : routingKey;
    this.start();
    this.api
      .create(exchange, queue, key)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.notice.set(
            result.created
              ? `Binding creado entre «${exchange}» y «${queue}».`
              : `Ese binding ya existe entre «${exchange}» y «${queue}»; no se ha duplicado.`,
          );
          this.finish();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  /** Elimina el binding sin pedir confirmación (CA-6.8). */
  protected remove(binding: Binding): void {
    if (this.busy()) {
      return;
    }
    this.start();
    this.api
      .delete(binding.exchange, binding.queue, binding.routingKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set(`Binding eliminado entre «${binding.exchange}» y «${binding.queue}».`);
          this.finish();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  private start(): void {
    this.busy.set(true);
    this.notice.set(null);
    this.actionError.set(null);
  }

  private finish(): void {
    this.busy.set(false);
    this.reload();
  }

  private fail(error: ApiError): void {
    this.connection.reportFailure(error);
    this.actionError.set(error.message);
    this.busy.set(false);
    if (error.kind === 'not-found' || error.kind === 'conflict') {
      this.reload();
      this.loadChoices();
    }
  }

  private failLoading(error: ApiError): void {
    this.connection.reportFailure(error);
    this.loadError.set(error.message);
  }
}
