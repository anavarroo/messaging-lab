import { FlowEvent, FlowNode, FlowNodeKind, FlowTick, FlowTopology } from './flow-model';

const COLUMNS: FlowNodeKind[] = ['PRODUCER', 'ROUTER', 'DESTINATION', 'CONSUMER'];

export const NODE_WIDTH = 190;
export const NODE_HEIGHT = 44;
const ROW_HEIGHT = 64;
const MARGIN = 24;
export const DIAGRAM_WIDTH = 960;

export interface Point {
  x: number;
  y: number;
}

export interface FlowLayout {
  /** Centro de cada nodo. */
  positions: Map<string, Point>;
  width: number;
  height: number;
}

/** Coloca los nodos en cuatro columnas, de izquierda a derecha: publicadores, enrutadores, destinos y consumidores. */
export function layoutFlow(topology: FlowTopology): FlowLayout {
  const positions = new Map<string, Point>();
  const columnWidth = DIAGRAM_WIDTH / COLUMNS.length;
  let rows = 1;
  COLUMNS.forEach((kind, column) => {
    const nodes: FlowNode[] = topology.nodes
      .filter((node) => node.kind === kind)
      .sort((a, b) => a.label.localeCompare(b.label));
    rows = Math.max(rows, nodes.length);
    nodes.forEach((node, row) => {
      positions.set(node.id, { x: columnWidth * (column + 0.5), y: MARGIN + NODE_HEIGHT / 2 + row * ROW_HEIGHT });
    });
  });
  return { positions, width: DIAGRAM_WIDTH, height: MARGIN * 2 + NODE_HEIGHT + (rows - 1) * ROW_HEIGHT };
}

/** Los comodines de un topic: `*` sustituye una palabra y `#` cero o más, separadas por puntos. */
export function topicMatches(pattern: string, key: string): boolean {
  const match = (p: string[], k: string[]): boolean => {
    if (p.length === 0) {
      return k.length === 0;
    }
    if (p[0] === '#') {
      return match(p.slice(1), k) || (k.length > 0 && match(p, k.slice(1)));
    }
    return k.length > 0 && (p[0] === '*' || p[0] === k[0]) && match(p.slice(1), k.slice(1));
  };
  return match(pattern.split('.'), key.split('.'));
}

/** ¿Pasa el evento el filtro? Se compara con su routing key y con los nombres de sus nodos, con comodines. */
export function matchesFilter(event: FlowEvent, filter: string, labels: ReadonlyMap<string, string>): boolean {
  const pattern = filter.trim().toLowerCase();
  if (!pattern) {
    return true;
  }
  const names = [event.router, event.producer, event.consumer, ...event.destinations]
    .filter((id): id is string => !!id)
    .map((id) => labels.get(id) ?? id);
  return [event.key ?? '', ...names].some((name) => topicMatches(pattern, name.toLowerCase()));
}

export interface Hop {
  from: string;
  to: string;
  /** 0 si sale al instante; 1 si espera a que acabe el tramo anterior. */
  stage: 0 | 1;
}

/** Tramos que recorre un evento entre nodos que existen en el diagrama. */
export function hopsOf(event: FlowEvent, known: ReadonlySet<string>): Hop[] {
  const hops: Hop[] = [];
  if (event.type === 'PUBLISH') {
    if (event.producer && event.router) {
      hops.push({ from: event.producer, to: event.router, stage: 0 });
    }
    if (event.router) {
      const stage = event.producer ? 1 : 0;
      event.destinations.forEach((destination) => hops.push({ from: event.router!, to: destination, stage }));
    }
  } else if (event.consumer) {
    event.destinations.forEach((destination) => hops.push({ from: destination, to: event.consumer!, stage: 0 }));
  }
  return hops.filter((hop) => known.has(hop.from) && known.has(hop.to));
}

/** Segundos de los que se saca el ritmo que se muestra: un solo segundo daría 0 a un emisor que publica cada 5 s. */
export const RATE_WINDOW_SECONDS = 5;

/** Ritmo medio por nodo de los últimos segundos. */
export function averageRates(
  ticks: readonly FlowTick[],
  pick: (tick: FlowTick) => Readonly<Record<string, number>>,
  seconds = RATE_WINDOW_SECONDS,
): Record<string, number> {
  const recent = ticks.slice(-seconds);
  const totals: Record<string, number> = {};
  for (const tick of recent) {
    for (const [id, rate] of Object.entries(pick(tick))) {
      totals[id] = (totals[id] ?? 0) + rate;
    }
  }
  return Object.fromEntries(Object.entries(totals).map(([id, total]) => [id, total / recent.length]));
}

export function labelsOf(topology: FlowTopology): Map<string, string> {
  return new Map(topology.nodes.map((node) => [node.id, node.label]));
}
