import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { ApiError } from '../../../core/http/api-error';
import { ConfirmDialog, ConfirmRequest } from '../../../shared/confirm-dialog/confirm-dialog';
import { ControlledServiceName, ServiceControlApi } from './service-control-api';

/** Cada cuánto se vuelve a comprobar si el servicio ya responde tras levantarlo, y hasta cuándo. */
export const RECHECK_MS = 3_000;
export const START_PATIENCE_MS = 120_000;

const STOP_WARNINGS: Record<ControlledServiceName, ConfirmRequest> = {
  RABBITMQ: {
    title: 'Parar RabbitMQ',
    message:
      'El laboratorio dejará de funcionar: se detienen los consumidores, el generador y el flujo. Los mensajes guardados se conservan y todo se recupera al levantarlo.',
    confirmLabel: 'Parar RabbitMQ',
  },
  ASSISTANT: {
    title: 'Parar el asistente de IA',
    message: 'El chat dejará de estar disponible y se libera la memoria del modelo.',
    confirmLabel: 'Parar el asistente',
  },
};

/**
 * Botones «Levantar» y «Parar» de un servicio en su tarjeta de «Estado». Solo aparecen con el Docker Compose del
 * proyecto; si no, se explica por qué. Tras levantarlo, pide a la tarjeta que vuelva a comprobar hasta que responda.
 */
@Component({
  selector: 'app-service-controls',
  imports: [ConfirmDialog],
  templateUrl: './service-controls.html',
  styleUrls: ['../rabbitmq-status/rabbitmq-status.css', './service-controls.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServiceControls implements OnInit {
  readonly service = input.required<ControlledServiceName>();
  /** Si el servicio responde ahora mismo (lo sabe la tarjeta). */
  readonly up = input.required<boolean>();
  /** Pide a la tarjeta que vuelva a comprobar el servicio. */
  readonly recheck = output<void>();

  protected readonly api = inject(ServiceControlApi);
  private readonly dialog = viewChild.required(ConfirmDialog);

  protected readonly busy = signal<'starting' | 'stopping' | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly slow = signal(false);
  protected readonly current = computed(() => this.api.status()?.services.find((s) => s.service === this.service()));

  private polling: ReturnType<typeof setInterval> | undefined;
  private patience: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
    effect(() => {
      if (this.busy() === 'starting' && this.up()) {
        this.stopPolling();
        this.busy.set(null);
      }
    });
  }

  ngOnInit(): void {
    this.api.load();
  }

  protected start(): void {
    this.run('starting', () => {
      this.slow.set(false);
      this.recheck.emit();
      this.polling = setInterval(() => this.recheck.emit(), RECHECK_MS);
      this.patience = setTimeout(() => {
        this.stopPolling();
        this.busy.set(null);
        this.slow.set(true);
      }, START_PATIENCE_MS);
    });
  }

  protected async stop(): Promise<void> {
    if (await this.dialog().open(STOP_WARNINGS[this.service()])) {
      this.run('stopping', () => {
        this.busy.set(null);
        this.recheck.emit();
      });
    }
  }

  private run(kind: 'starting' | 'stopping', done: () => void): void {
    this.error.set(null);
    this.busy.set(kind);
    this.api.act(this.service(), kind === 'starting' ? 'START' : 'STOP').subscribe({
      next: () => done(),
      error: (error: ApiError) => {
        this.busy.set(null);
        this.error.set(error.message);
      },
    });
  }

  private stopPolling(): void {
    clearInterval(this.polling);
    clearTimeout(this.patience);
  }
}
