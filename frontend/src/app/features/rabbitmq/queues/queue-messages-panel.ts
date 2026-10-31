import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timer } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { QueueInspection, QueueMessage, QueuesApi } from './queues-api';

/** Cada cuánto se vuelven a leer los mensajes mientras el panel está abierto. */
export const MESSAGES_REFRESH_MS = 2_000;

/**
 * Panel con los mensajes listos de una cola, leídos sin consumirlos (RF-8). Los cuerpos se muestran con
 * interpolación de Angular, que los escapa: nunca se interpretan como HTML (RNF-5).
 */
@Component({
  selector: 'app-queue-messages-panel',
  templateUrl: './queue-messages-panel.html',
  styleUrls: ['../../../shared/lab-area.css', './queue-messages-panel.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QueueMessagesPanel {
  private readonly api = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);

  readonly queue = input.required<string>();
  readonly closed = output<void>();

  protected readonly inspection = signal<QueueInspection | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Releer los mensajes solo cada pocos segundos; se puede desactivar. */
  protected readonly autoRefresh = signal(true);

  /** Identifica la última lectura pedida: una respuesta tardía de una cola anterior se ignora. */
  private lastRequest = 0;

  constructor() {
    timer(MESSAGES_REFRESH_MS, MESSAGES_REFRESH_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.autoRefresh() && !this.loading()) {
          this.load(this.queue(), true);
        }
      });
    effect(() => {
      const queue = this.queue();
      untracked(() => {
        this.inspection.set(null);
        this.load(queue);
      });
    });
  }

  /** Vuelve a leer la cola, por ejemplo tras vaciarla. */
  refresh(): void {
    this.load(this.queue());
  }

  protected exchangeOf(message: QueueMessage): string {
    return message.exchange === '' ? '(exchange por defecto)' : message.exchange;
  }

  protected toggleAutoRefresh(enabled: boolean): void {
    this.autoRefresh.set(enabled);
  }

  /** Una lectura periódica (`silent`) no muestra «Cargando» ni parpadea el panel. */
  private load(queue: string, silent = false): void {
    const request = ++this.lastRequest;
    if (!silent) {
      this.loading.set(true);
    }
    this.error.set(null);
    this.api
      .inspect(queue)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (inspection) => {
          if (request === this.lastRequest) {
            this.inspection.set(inspection);
            this.loading.set(false);
          }
        },
        error: (error: ApiError) => {
          if (request === this.lastRequest) {
            this.connection.reportFailure(error);
            this.error.set(error.message);
            this.loading.set(false);
          }
        },
      });
  }
}
