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
import { ApiError } from '../../../../core/http/api-error';
import { RabbitMqConnection } from '../../connection/rabbitmq-connection';
import { QueuesApi } from '../../queues/queues-api';
import { AckMode, ActionOutcome, MessageAction } from '../consumers-api';
import {
  ConsumerSessionService,
  ConsumerView,
  MAX_MESSAGES_PER_CONSUMER,
  ReceivedMessage,
} from '../consumer-session.service';

const MAX_NAME_LENGTH = 255;

/** Qué se le cuenta al usuario según lo que ha pasado con el mensaje (CA-9.5 a CA-9.7). */
const OUTCOME_TEXTS: Record<ActionOutcome, string> = {
  CONFIRMED: 'Confirmado: el mensaje se ha eliminado de la cola.',
  REQUEUED: 'Rechazado y reencolado: ha vuelto a la cola y se recibirá de nuevo marcado como reentregado.',
  DEAD_LETTERED: 'Rechazado sin reencolar: el mensaje se ha enviado a la dead-letter queue de la cola.',
  DISCARDED:
    'El mensaje se ha descartado: se rechazó sin reencolar y la cola no tiene dead-letter queue.',
};

/** Área de consumidores: inicia consumidores y permite decidir qué hacer con cada mensaje (RF-9). */
@Component({
  selector: 'app-consumers-page',
  imports: [ReactiveFormsModule],
  templateUrl: './consumers-page.html',
  styleUrls: ['../../shared/lab-area.css', './consumers-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsumersPage implements OnInit {
  protected readonly session = inject(ConsumerSessionService);
  private readonly queuesApi = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly maxMessages = MAX_MESSAGES_PER_CONSUMER;

  protected readonly form = inject(NonNullableFormBuilder).group({
    queue: [''],
    ackMode: ['MANUAL' as AckMode],
    name: [''],
  });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly queueNames = signal<string[]>([]);
  protected readonly queueLoadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly reconnecting = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly submitted = signal(false);

  protected readonly queueError = computed(() => (this.formValue().queue ? null : 'Elige la cola que consumir.'));

  protected readonly nameError = computed(() => {
    const name = this.formValue().name ?? '';
    if (name.trim() === '') {
      return 'El nombre del consumidor no puede estar vacío.';
    }
    if (name.length > MAX_NAME_LENGTH) {
      return `El nombre del consumidor no puede superar los ${MAX_NAME_LENGTH} caracteres.`;
    }
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) {
      return 'El nombre del consumidor no puede contener caracteres de control.';
    }
    if (this.session.activeConsumers().some((consumer) => consumer.info.name === name)) {
      return 'Ya hay un consumidor activo con ese nombre.';
    }
    return null;
  });

  ngOnInit(): void {
    this.form.controls.name.setValue(this.suggestName());
    this.loadQueues();
  }

  protected loadQueues(): void {
    this.queuesApi
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (queues) => {
          this.queueNames.set(queues.map((queue) => queue.name));
          this.queueLoadError.set(null);
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.queueLoadError.set(error.message);
        },
      });
  }

  protected start(): void {
    this.submitted.set(true);
    if (this.busy() || this.queueError() || this.nameError()) {
      return;
    }
    const { queue, ackMode, name } = this.form.getRawValue();
    this.busy.set(true);
    this.notice.set(null);
    this.actionError.set(null);
    this.session
      .start(name, queue, ackMode)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set(`Consumidor «${name}» iniciado sobre la cola «${queue}».`);
          this.submitted.set(false);
          this.form.controls.name.setValue(this.suggestName());
          this.busy.set(false);
        },
        error: (error: ApiError) => {
          this.actionError.set(error.message);
          this.busy.set(false);
          if (error.kind === 'not-found') {
            this.loadQueues();
          }
        },
      });
  }

  protected stop(consumer: ConsumerView): void {
    this.actionError.set(null);
    this.session
      .stop(consumer.info.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: (error: ApiError) => this.actionError.set(error.message) });
  }

  protected resolve(consumer: ConsumerView, message: ReceivedMessage, action: MessageAction): void {
    this.session
      .resolve(consumer.info.id, message.deliveryTag, action)
      .pipe(takeUntilDestroyed(this.destroyRef))
      // el estado y el error de cada mensaje los gestiona el servicio
      .subscribe({ error: () => undefined });
  }

  protected reconnect(): void {
    if (this.reconnecting()) {
      return;
    }
    this.reconnecting.set(true);
    this.session
      .reconnect()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.reconnecting.set(false),
        error: () => this.reconnecting.set(false),
      });
  }

  /** Los mensajes se muestran del más reciente al más antiguo para verlos sin desplazarse. */
  protected newestFirst(consumer: ConsumerView): ReceivedMessage[] {
    return [...consumer.messages].reverse();
  }

  protected modeLabel(mode: AckMode): string {
    return mode === 'MANUAL' ? 'Manual' : 'Automático';
  }

  protected outcomeText(outcome: ActionOutcome): string {
    return OUTCOME_TEXTS[outcome];
  }

  protected exchangeOf(message: ReceivedMessage): string {
    return message.exchange === '' ? '(exchange por defecto)' : message.exchange;
  }

  private suggestName(): string {
    const used = new Set(this.session.consumers().map((consumer) => consumer.info.name));
    for (let n = this.session.consumers().length + 1; ; n++) {
      const candidate = `consumidor-${n}`;
      if (!used.has(candidate)) {
        return candidate;
      }
    }
  }
}
