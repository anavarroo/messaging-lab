import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';

export type ControlledServiceName = 'RABBITMQ' | 'ASSISTANT';

export interface ControlledService {
  service: ControlledServiceName;
  state: 'RUNNING' | 'STOPPED' | 'MISSING';
  /** Por qué no existe, con el comando para crearlo. */
  message?: string | null;
}

export interface ControlStatus {
  available: boolean;
  /** Por qué no se puede parar ni levantar desde aquí, con el comando equivalente. */
  reason?: string | null;
  services: ControlledService[];
}

/** Parar y levantar RabbitMQ y el asistente (solo con el Docker Compose del proyecto). Lo comparten las dos tarjetas. */
@Injectable({ providedIn: 'root' })
export class ServiceControlApi {
  private readonly http = inject(HttpClient);

  /** El último estado conocido; `null` mientras no se sabe o si el backend no responde. */
  readonly status = signal<ControlStatus | null>(null);

  load(): void {
    this.http
      .get<ControlStatus>('/api/control/services')
      .pipe(requestTimeout(), translateApiErrors())
      .subscribe({ next: (status) => this.status.set(status), error: () => this.status.set(null) });
  }

  /** Arranca o para el servicio; responde cuando Docker ha terminado (el servicio puede tardar algo más en estar listo). */
  act(service: ControlledServiceName, action: 'START' | 'STOP'): Observable<ControlledService> {
    // sin el tiempo máximo normal: parar RabbitMQ espera a que se detenga del todo, y puede tardar más
    return this.http
      .post<ControlledService>(`/api/control/services/${service.toLowerCase()}/actions`, { action })
      .pipe(
        translateApiErrors(),
        tap((updated) =>
          this.status.update((status) =>
            status && { ...status, services: status.services.map((s) => (s.service === updated.service ? updated : s)) },
          ),
        ),
      );
  }
}
