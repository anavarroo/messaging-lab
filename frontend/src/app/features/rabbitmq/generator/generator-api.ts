import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { translateApiErrors } from '../../../core/http/api-error';
import { requestTimeout } from '../../../core/http/request-timeout';
import {
  EmitterAction,
  EmitterFormValue,
  EmitterView,
  Interval,
  PreviewResult,
  TemplatePreset,
} from '../../../shared/generator/emitter-view';

/** Dónde publica un emisor de RabbitMQ (`EmitterDestination`). */
export interface EmitterDestination {
  type: 'EXCHANGE' | 'QUEUE';
  exchange?: string | null;
  routingKey?: string | null;
  queue?: string | null;
}

/** Emisor simulado con su estado real y sus contadores (`Emitter`). */
export interface Emitter extends EmitterView {
  destination: EmitterDestination;
  template: string;
  maxMessages?: number | null;
  maxDurationSeconds?: number | null;
  onlyWhenChanged: boolean;
}

/** Ajustes del generador y uso actual (`GeneratorSettings`). */
export interface GeneratorSettings {
  maxRatePerSecond: number;
  absoluteMaxRatePerSecond: number;
  ratePerSecondInUse: number;
  maxEmitters: number;
  maxSimulatedConsumers: number;
}

/** Emisor que se quiere crear (`EmitterRequest`). */
export interface EmitterRequest extends EmitterFormValue {
  destination: EmitterDestination;
}

export type SimulatedConsumerStatus = 'STOPPED' | 'RUNNING' | 'FAILED';

/** Consumidor simulado que se quiere crear (`SimulatedConsumerRequest`). */
export interface SimulatedConsumerRequest {
  name: string;
  queue: string;
  processing: Interval;
  failurePercent: number;
  onFailure: 'REQUEUE' | 'REJECT';
}

/** Consumidor simulado con su estado y sus contadores (`SimulatedConsumer`). */
export interface SimulatedConsumer extends SimulatedConsumerRequest {
  id: string;
  status: SimulatedConsumerStatus;
  processed: number;
  failed: number;
  ratePerSecond: number;
  stopMessage?: string | null;
}

const URL = '/api/rabbitmq/generator';

/** Operaciones del generador de tráfico. Los fallos llegan como `ApiError` con un mensaje en español. */
@Injectable({ providedIn: 'root' })
export class GeneratorApi {
  private readonly http = inject(HttpClient);

  listEmitters(): Observable<Emitter[]> {
    return this.http.get<Emitter[]>(`${URL}/emitters`).pipe(requestTimeout(), translateApiErrors());
  }

  /** Inicia, pausa, reanuda o para un emisor; devuelve su estado nuevo. */
  actOnEmitter(id: string, action: EmitterAction): Observable<Emitter> {
    return this.http
      .post<Emitter>(`${URL}/emitters/${encodeURIComponent(id)}/actions`, { action })
      .pipe(requestTimeout(), translateApiErrors());
  }

  createEmitter(request: EmitterRequest): Observable<Emitter> {
    return this.http.post<Emitter>(`${URL}/emitters`, request).pipe(requestTimeout(), translateApiErrors());
  }

  deleteEmitter(id: string): Observable<void> {
    return this.http
      .delete<void>(`${URL}/emitters/${encodeURIComponent(id)}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }

  getSettings(): Observable<GeneratorSettings> {
    return this.http.get<GeneratorSettings>(`${URL}/settings`).pipe(requestTimeout(), translateApiErrors());
  }

  /** Cambia el límite global de mensajes por segundo. */
  updateSettings(maxRatePerSecond: number): Observable<GeneratorSettings> {
    return this.http
      .put<GeneratorSettings>(`${URL}/settings`, { maxRatePerSecond })
      .pipe(requestTimeout(), translateApiErrors());
  }

  /** Para todos los emisores y consumidores simulados. */
  stopAll(): Observable<void> {
    return this.http.post<void>(`${URL}/stop-all`, null).pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }

  listTemplates(): Observable<TemplatePreset[]> {
    return this.http.get<TemplatePreset[]>(`${URL}/templates`).pipe(requestTimeout(), translateApiErrors());
  }

  /** Genera un ejemplo del mensaje; una plantilla inválida llega como error con el motivo y su posición. */
  previewTemplate(template: string): Observable<PreviewResult> {
    return this.http
      .post<PreviewResult>(`${URL}/templates/preview`, { template })
      .pipe(requestTimeout(), translateApiErrors());
  }

  listConsumers(): Observable<SimulatedConsumer[]> {
    return this.http.get<SimulatedConsumer[]>(`${URL}/consumers`).pipe(requestTimeout(), translateApiErrors());
  }

  createConsumer(request: SimulatedConsumerRequest): Observable<SimulatedConsumer> {
    return this.http.post<SimulatedConsumer>(`${URL}/consumers`, request).pipe(requestTimeout(), translateApiErrors());
  }

  actOnConsumer(id: string, action: 'START' | 'STOP'): Observable<SimulatedConsumer> {
    return this.http
      .post<SimulatedConsumer>(`${URL}/consumers/${encodeURIComponent(id)}/actions`, { action })
      .pipe(requestTimeout(), translateApiErrors());
  }

  deleteConsumer(id: string): Observable<void> {
    return this.http
      .delete<void>(`${URL}/consumers/${encodeURIComponent(id)}`)
      .pipe(requestTimeout(), translateApiErrors(), map(() => undefined));
  }
}
