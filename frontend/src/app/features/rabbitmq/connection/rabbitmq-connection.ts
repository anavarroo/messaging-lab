import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { ApiError, toApiError, translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';

/** Estado de la comunicación del backend con RabbitMQ, tal como lo define el contrato. */
interface RabbitMqStatus {
  status: 'CONNECTED';
  version: string;
}

function isRabbitMqStatus(value: unknown): value is RabbitMqStatus {
  const candidate = value as Record<string, unknown> | null;
  return (
    typeof candidate === 'object' &&
    candidate !== null &&
    candidate['status'] === 'CONNECTED' &&
    typeof candidate['version'] === 'string' &&
    candidate['version'] !== ''
  );
}

export type ConnectionState =
  | { kind: 'loading'; retrying: boolean }
  | { kind: 'connected'; version: string }
  | { kind: 'unavailable'; message: string };

const INVALID_RESPONSE = 'No se ha podido comprobar la conexión con RabbitMQ.';
const RABBITMQ_TIMEOUT = 'RabbitMQ no está disponible: la consulta ha superado el tiempo máximo de espera.';

/**
 * Estado de la conexión con RabbitMQ del laboratorio (RF-2). Se provee en la ruta del laboratorio, de modo
 * que se consulta al entrar y se descarta al salir.
 *
 * Si es el propio backend el que no responde, el mensaje es el del backend (CA-2.8) y no el de RabbitMQ.
 */
@Injectable()
export class RabbitMqConnection {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  private readonly current = signal<ConnectionState>({ kind: 'loading', retrying: false });

  readonly state = this.current.asReadonly();

  private readonly everConnected = signal(false);

  /** Si ya se ha llegado a conectar alguna vez: a partir de ahí las áreas se mantienen aunque la conexión caiga. */
  readonly hasConnected = this.everConnected.asReadonly();

  /** Las operaciones del laboratorio solo están disponibles con RabbitMQ conectado (CA-2.3, CA-2.4). */
  readonly operationsEnabled = computed(() => this.current().kind === 'connected');

  /** Consulta si el backend puede comunicarse con RabbitMQ. */
  check(): void {
    this.load(false);
  }

  /** Repite la consulta tras un fallo; se ignora si no hay un fallo que reintentar o ya hay otra en curso (CA-2.7). */
  retry(): void {
    if (this.current().kind === 'unavailable') {
      this.load(true);
    }
  }

  /**
   * Una operación del laboratorio ha fallado: si es porque RabbitMQ o el backend no están disponibles, la
   * interfaz pasa a «no disponible» para ofrecer reintentar (CA-11.1). Otros fallos no cambian el estado.
   */
  reportFailure(error: unknown): void {
    const apiError = toApiError(error);
    if (apiError.kind === 'broker-unavailable' || apiError.kind === 'backend-unavailable') {
      this.current.set({ kind: 'unavailable', message: apiError.message });
    }
  }

  private load(retrying: boolean): void {
    this.current.set({ kind: 'loading', retrying });
    this.http
      .get<unknown>('/api/rabbitmq/status')
      .pipe(
        requestTimeout(),
        translateApiErrors(),
        map((body) => {
          if (!isRabbitMqStatus(body)) {
            throw new ApiError('unknown', INVALID_RESPONSE);
          }
          return body;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (status) => {
          this.everConnected.set(true);
          this.current.set({ kind: 'connected', version: status.version });
        },
        error: (error: ApiError) =>
          this.current.set({
            kind: 'unavailable',
            message: error.kind === 'timeout' ? RABBITMQ_TIMEOUT : error.message,
          }),
      });
  }
}
