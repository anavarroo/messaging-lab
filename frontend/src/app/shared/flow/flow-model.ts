import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Modelo del visualizador de flujo. Es neutral respecto al sistema de mensajería: en RabbitMQ el enrutador es un
 * exchange y el destino una cola; en Kafka serán un topic y una partición. Coincide con los esquemas `Flow*` del
 * contrato, que cada laboratorio expone bajo su propia ruta.
 */

export type FlowNodeKind = 'PRODUCER' | 'ROUTER' | 'DESTINATION' | 'CONSUMER';

export interface FlowNode {
  id: string;
  kind: FlowNodeKind;
  label: string;
  detail?: string | null;
}

export interface FlowEdge {
  from: string;
  to: string;
  label?: string | null;
}

export interface FlowTopology {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

export interface FlowEvent {
  id: number;
  time: string;
  type: 'PUBLISH' | 'DELIVER';
  producer?: string | null;
  router?: string | null;
  destinations: string[];
  consumer?: string | null;
  key?: string;
  sizeBytes: number;
  redelivered: boolean;
  unrouted: boolean;
  /** Cuerpo truncado: es texto no fiable y nunca se interpreta como HTML. */
  body?: string;
  bodyTruncated?: boolean;
  properties?: Record<string, unknown>;
}

/** Resumen de un segundo, indexado por id de nodo. */
export interface FlowTick {
  time: string;
  published: Record<string, number>;
  delivered: Record<string, number>;
  ready: Record<string, number>;
  /** Eventos que no se enviaron en ese segundo por superar el máximo. */
  omitted: number;
}

export interface FlowSourceStatus {
  state: 'UNAVAILABLE' | 'DISABLED' | 'ACTIVE';
  hint?: string | null;
}

/** Lo que llega por el flujo en tiempo real. */
export type FlowMessage =
  | { kind: 'status'; status: FlowSourceStatus }
  | { kind: 'topology'; topology: FlowTopology }
  | { kind: 'tick'; tick: FlowTick }
  | { kind: 'event'; event: FlowEvent };

/**
 * De dónde salen los datos. Cada laboratorio aporta la suya (RabbitMQ, Kafka...) y el resto del visualizador no sabe
 * a qué sistema pertenece.
 */
export interface FlowSource {
  /** Eventos recientes, el más reciente primero. */
  recent(): Observable<FlowEvent[]>;
  /**
   * Flujo en tiempo real: se conecta al suscribirse y se desconecta al cancelar. Al conectar envía el estado, la
   * topología y los ticks recientes. Si se corta, el observable termina con error y quien lo use reintenta.
   */
  stream(): Observable<FlowMessage>;
}

export const FLOW_SOURCE = new InjectionToken<FlowSource>('FlowSource');

/** Palabras con las que cada laboratorio llama a las cosas (en RabbitMQ: exchange y cola). */
export interface FlowVocabulary {
  producer: string;
  router: string;
  destination: string;
  consumer: string;
  key: string;
}

export const DEFAULT_VOCABULARY: FlowVocabulary = {
  producer: 'Publicador',
  router: 'Enrutador',
  destination: 'Destino',
  consumer: 'Consumidor',
  key: 'Clave',
};
