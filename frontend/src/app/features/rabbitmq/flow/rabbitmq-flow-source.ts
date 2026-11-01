import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';
import { FlowEvent, FlowMessage, FlowSource } from '../../../shared/flow/flow-model';
import { EVENT_SOURCE_FACTORY } from '../consumers/event-source-factory';

const URL = '/api/rabbitmq/flow';

/** Eventos del flujo en tiempo real y el mensaje del visualizador al que corresponde cada uno. */
const STREAM_EVENTS: Record<string, (data: unknown) => FlowMessage> = {
  status: (data) => ({ kind: 'status', status: data as never }),
  topology: (data) => ({ kind: 'topology', topology: data as never }),
  tick: (data) => ({ kind: 'tick', tick: data as never }),
  'flow-event': (data) => ({ kind: 'event', event: data as never }),
};

/** Fuente de datos del visualizador de flujo del Laboratorio RabbitMQ: la API de trazado del backend. */
@Injectable()
export class RabbitMqFlowSource implements FlowSource {
  private readonly http = inject(HttpClient);
  private readonly createEventSource = inject(EVENT_SOURCE_FACTORY);

  recent(): Observable<FlowEvent[]> {
    return this.http
      .get<FlowEvent[]>(`${URL}/events`, { params: { limit: 200 } })
      .pipe(requestTimeout(), translateApiErrors());
  }

  /** Abre el flujo al suscribirse y lo cierra al cancelar; si se corta, termina con error para que se reintente. */
  stream(): Observable<FlowMessage> {
    return new Observable<FlowMessage>((subscriber) => {
      const source = this.createEventSource(`${URL}/stream`);
      for (const [name, toMessage] of Object.entries(STREAM_EVENTS)) {
        source.addEventListener(name, (event) => {
          try {
            subscriber.next(toMessage(JSON.parse(event.data)));
          } catch {
            // un mensaje que no se puede leer no debe cortar el flujo
          }
        });
      }
      source.onerror = () => subscriber.error(new Error('Se ha cortado el flujo de datos.'));
      return () => source.close();
    });
  }
}
