import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { timer } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { ConfirmDialog } from '../../../shared/confirm-dialog/confirm-dialog';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { asValidator, validateName } from '../validation/validation';
import { QueueMessagesPanel } from './queue-messages-panel';
import { Queue, QueuesApi } from './queues-api';

/** Cada cuánto se actualizan solos los contadores mientras el área está abierta. */
export const QUEUES_REFRESH_MS = 2_000;

const OWN_DEAD_LETTER_QUEUE = 'Una cola no puede ser su propia dead-letter queue.';

/** Área de colas: listado con contadores, creación, vaciado, borrado e inspección (RF-5, RF-8). */
@Component({
  selector: 'app-queues-page',
  imports: [ReactiveFormsModule, ConfirmDialog, QueueMessagesPanel],
  templateUrl: './queues-page.html',
  styleUrls: ['../shared/lab-area.css', './queues-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QueuesPage implements OnInit {
  private readonly api = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = viewChild.required(ConfirmDialog);
  private readonly panel = viewChild(QueueMessagesPanel);

  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      name: ['', asValidator((name) => validateName(name, 'de la cola'))],
      deadLetterQueue: [''],
    },
    {
      // se valida entre los dos campos: una cola no puede ser su propia dead-letter queue (CA-5.5)
      validators: (group): ValidationErrors | null => {
        const name = group.get('name')?.value as string;
        const dead = group.get('deadLetterQueue')?.value as string;
        return dead !== '' && dead === name ? { ownDeadLetterQueue: OWN_DEAD_LETTER_QUEUE } : null;
      },
    },
  );

  /** Último listado recibido; se conserva aunque una operación o una recarga fallen (CA-11.6). */
  protected readonly queues = signal<Queue[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  /** Hay una operación en curso: se impide repetirla (CA-11.5). */
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly submitted = signal(false);
  /** Cola cuyos mensajes se están inspeccionando. */
  protected readonly inspected = signal<string | null>(null);

  protected readonly names = computed(() => (this.queues() ?? []).map((queue) => queue.name));

  ngOnInit(): void {
    this.reload();
    // lo que publican los emisores del generador o consumen los consumidores se ve sin pulsar nada
    timer(QUEUES_REFRESH_MS, QUEUES_REFRESH_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (!this.loading() && !this.busy()) {
          this.reload(true);
        }
      });
  }

  protected nameError(): string | null {
    const error = this.form.controls.name.errors?.['invalid'];
    return typeof error === 'string' ? error : null;
  }

  protected ownDeadLetterQueueError(): string | null {
    const error = this.form.errors?.['ownDeadLetterQueue'];
    return typeof error === 'string' ? error : null;
  }

  protected showErrors(): boolean {
    return this.submitted() || this.form.controls.name.touched;
  }

  /**
   * Recarga los contadores: a petición del usuario (CA-8.2), tras cada operación y cada pocos segundos. La recarga
   * periódica es silenciosa: no muestra «Cargando» ni borra el listado si falla.
   */
  protected reload(silent = false): void {
    if (!silent) {
      this.loading.set(true);
    }
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (queues) => {
          this.queues.set(queues);
          this.loadError.set(null);
          this.loading.set(false);
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.loadError.set(error.message);
          this.loading.set(false);
        },
      });
  }

  protected create(): void {
    this.submitted.set(true);
    if (this.busy() || this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, deadLetterQueue } = this.form.getRawValue();
    this.start();
    this.api
      .create(name, deadLetterQueue)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.notice.set(
            result.created
              ? `Cola «${name}» creada.`
              : `La cola «${name}» ya existe con la misma configuración; no se ha duplicado.`,
          );
          if (result.created) {
            this.form.reset({ name: '', deadLetterQueue: '' });
            this.submitted.set(false);
          }
          this.finish();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  protected inspect(queue: Queue): void {
    this.inspected.set(queue.name);
  }

  protected async purge(queue: Queue): Promise<void> {
    if (this.busy()) {
      return;
    }
    const confirmed = await this.dialog().open({
      title: `Vaciar la cola «${queue.name}»`,
      message:
        `Se eliminarán los ${queue.ready} mensajes listos de la cola. La cola y sus bindings se conservan, ` +
        'y los mensajes pendientes de confirmar por un consumidor no se tocan.',
      confirmLabel: 'Vaciar',
    });
    if (!confirmed) {
      return;
    }
    this.start();
    this.api
      .purge(queue.name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set(`Cola «${queue.name}» vaciada.`);
          this.finish();
          this.panel()?.refresh();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  protected async remove(queue: Queue): Promise<void> {
    if (this.busy()) {
      return;
    }
    // las colas que usan esta como dead-letter queue, calculadas con el último listado (CA-5.9)
    const affected = (this.queues() ?? [])
      .filter((other) => other.deadLetterQueue === queue.name)
      .map((other) => other.name);
    const base = 'Se eliminará la cola con todos sus mensajes y sus bindings.';
    const confirmed = await this.dialog().open({
      title: `Eliminar la cola «${queue.name}»`,
      message:
        affected.length === 0
          ? base
          : `${base} Es la dead-letter queue de las colas siguientes: los mensajes que rechacen sin reencolar se descartarán.`,
      items: affected,
      confirmLabel: 'Eliminar',
    });
    if (!confirmed) {
      return;
    }
    this.start();
    this.api
      .delete(queue.name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set(`Cola «${queue.name}» eliminada.`);
          if (this.inspected() === queue.name) {
            this.inspected.set(null);
          }
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

  /** Termina una operación correcta y recarga los contadores (CA-8.8). */
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
    }
  }
}
