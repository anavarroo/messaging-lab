import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqHealthApi, TracingStatus } from './rabbitmq-health-api';

type Connection = { kind: 'checking' } | { kind: 'connected'; version: string } | { kind: 'unavailable'; message: string };

type Tracing = { kind: 'checking' } | { kind: 'known'; status: TracingStatus } | { kind: 'unknown'; message: string };

/** Comprueba que RabbitMQ está levantado y si tiene el trazado activo, que es lo que necesita el visualizador de flujo. */
@Component({
  selector: 'app-rabbitmq-status',
  templateUrl: './rabbitmq-status.html',
  styleUrl: './rabbitmq-status.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RabbitMqStatus implements OnInit {
  private readonly api = inject(RabbitMqHealthApi);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly connection = signal<Connection>({ kind: 'checking' });
  protected readonly tracing = signal<Tracing>({ kind: 'checking' });
  private started = false;

  ngOnInit(): void {
    this.check();
  }

  protected checking(): boolean {
    return this.connection().kind === 'checking' || this.tracing().kind === 'checking';
  }

  /** Vuelve a comprobar; se ignora mientras haya una comprobación en curso. */
  protected check(): void {
    if (this.checking() && this.started) {
      return;
    }
    this.started = true;
    this.connection.set({ kind: 'checking' });
    this.tracing.set({ kind: 'checking' });
    this.api
      .version()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (version) => this.connection.set({ kind: 'connected', version }),
        error: (error: ApiError) => this.connection.set({ kind: 'unavailable', message: error.message }),
      });
    this.api
      .tracing()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => this.tracing.set({ kind: 'known', status }),
        error: (error: ApiError) => this.tracing.set({ kind: 'unknown', message: error.message }),
      });
  }
}
