import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BackendStatus } from '../../../core/status/backend-status';
import { StatusApiService, StatusError } from '../../../core/status/status-api.service';
import { AssistantStatusCard } from '../assistant-status/assistant-status';
import { RabbitMqStatus } from '../rabbitmq-status/rabbitmq-status';

type StatusState =
  | { kind: 'loading'; retrying: boolean }
  | { kind: 'success'; data: BackendStatus }
  | { kind: 'error'; message: string };

const GENERIC_ERROR = 'No se ha podido obtener el estado del backend.';

@Component({
  selector: 'app-status-page',
  imports: [RabbitMqStatus, AssistantStatusCard],
  templateUrl: './status-page.html',
  styleUrl: './status-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPage implements OnInit {
  private readonly statusApi = inject(StatusApiService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<StatusState>({ kind: 'loading', retrying: false });

  ngOnInit(): void {
    this.load(false);
  }

  /** Repite la consulta de estado; se ignora mientras haya otra en curso (CA-3.6). */
  protected retry(): void {
    if (this.state().kind === 'error') {
      this.load(true);
    }
  }

  private load(retrying: boolean): void {
    this.state.set({ kind: 'loading', retrying });
    this.statusApi
      .getStatus()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.state.set({ kind: 'success', data }),
        error: (error: unknown) =>
          this.state.set({
            kind: 'error',
            message: error instanceof StatusError ? error.message : GENERIC_ERROR,
          }),
      });
  }
}
