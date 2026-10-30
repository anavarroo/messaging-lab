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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangeType, ExchangesApi } from '../exchanges/exchanges-api';
import { QueuesApi } from '../queues/queues-api';
import { validateRoutingKey } from '../validation/validation';
import { MAX_BODY_BYTES, PublishApi } from './publish-api';

const TYPE_LABELS: Record<ExchangeType, string> = { DIRECT: 'Direct', FANOUT: 'Fanout', TOPIC: 'Topic' };

const encoder = new TextEncoder();

/**
 * Valor de «ningún exchange elegido». El exchange por defecto tiene el nombre vacío, así que el vacío no sirve;
 * un carácter de control sí, porque ningún nombre válido puede contenerlo.
 */
const NO_EXCHANGE = '\u0000';

type Outcome = { routed: boolean; exchange: string };

/** Área de publicación: envía un mensaje a un exchange e informa de si llegó a alguna cola (RF-7). */
@Component({
  selector: 'app-publish-page',
  imports: [ReactiveFormsModule],
  templateUrl: './publish-page.html',
  styleUrls: ['../shared/lab-area.css', './publish-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublishPage implements OnInit {
  private readonly api = inject(PublishApi);
  private readonly exchangesApi = inject(ExchangesApi);
  private readonly queuesApi = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly typeLabels = TYPE_LABELS;
  protected readonly noExchange = NO_EXCHANGE;
  protected readonly maxBytes = MAX_BODY_BYTES;

  protected readonly form = inject(NonNullableFormBuilder).group({
    exchange: [NO_EXCHANGE],
    routingKey: [''],
    body: [''],
  });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly exchanges = signal<Exchange[] | null>(null);
  protected readonly queueNames = signal<string[]>([]);
  protected readonly loadError = signal<string | null>(null);
  /** Hay una publicación en curso: se impide enviar otra (CA-7.8). */
  protected readonly busy = signal(false);
  protected readonly outcome = signal<Outcome | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly submitted = signal(false);

  protected readonly selectedExchange = computed(() =>
    (this.exchanges() ?? []).find((exchange) => exchange.name === this.formValue().exchange),
  );
  protected readonly isDefaultExchange = computed(() => this.selectedExchange()?.name === '');
  protected readonly usesRoutingKey = computed(() => this.selectedExchange()?.type !== 'FANOUT');

  protected readonly bodyBytes = computed(() => encoder.encode(this.formValue().body ?? '').length);

  protected readonly bodyError = computed(() => {
    const body = this.formValue().body ?? '';
    if (body === '') {
      return 'El cuerpo del mensaje no puede estar vacío.';
    }
    if (this.bodyBytes() > MAX_BODY_BYTES) {
      return `El cuerpo del mensaje no puede ocupar más de 64 KB (${MAX_BODY_BYTES} bytes en UTF-8).`;
    }
    return null;
  });

  protected readonly routingKeyError = computed(() =>
    this.usesRoutingKey() ? validateRoutingKey(this.formValue().routingKey) : null,
  );

  protected readonly exchangeError = computed(() =>
    this.selectedExchange() ? null : 'Elige el exchange en el que publicar.',
  );

  ngOnInit(): void {
    this.loadChoices();
  }

  protected displayName(exchange: Exchange): string {
    return exchange.name === '' ? '(exchange por defecto)' : exchange.name;
  }

  protected loadChoices(): void {
    this.exchangesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (exchanges) => {
          this.exchanges.set(exchanges);
          this.loadError.set(null);
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.loadError.set(error.message);
        },
      });
    this.queuesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (queues) => this.queueNames.set(queues.map((queue) => queue.name)),
        // los nombres de las colas solo sirven de sugerencia: sin ellos se puede escribir a mano
        error: () => undefined,
      });
  }

  protected publish(): void {
    this.submitted.set(true);
    if (this.busy() || this.exchangeError() || this.bodyError() || this.routingKeyError()) {
      return;
    }
    const { exchange, routingKey, body } = this.form.getRawValue();
    this.busy.set(true);
    this.outcome.set(null);
    this.actionError.set(null);
    this.api
      .publish(exchange, this.usesRoutingKey() ? routingKey : '', body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.outcome.set({ routed: result.routed, exchange });
          this.busy.set(false);
        },
        // el contenido escrito se conserva: el formulario no se toca (CA-7.6)
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.actionError.set(error.message);
          this.busy.set(false);
          if (error.kind === 'not-found') {
            this.loadChoices();
          }
        },
      });
  }
}
