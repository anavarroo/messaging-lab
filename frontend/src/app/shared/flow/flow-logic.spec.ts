import { DIAGRAM_WIDTH, averageRates, hopsOf, labelsOf, layoutFlow, matchesFilter, topicMatches } from './flow-logic';
import { FlowEvent, FlowNode, FlowTick, FlowTopology } from './flow-model';

const node = (id: string, kind: FlowNode['kind'], label = id): FlowNode => ({ id, kind, label });

const event = (extra: Partial<FlowEvent> = {}): FlowEvent => ({
  id: 1,
  time: '2026-10-30T10:00:00Z',
  type: 'PUBLISH',
  producer: 'p',
  router: 'r',
  destinations: ['d1', 'd2'],
  consumer: null,
  key: 'lab.temperatura',
  sizeBytes: 5,
  redelivered: false,
  unrouted: false,
  ...extra,
});

describe('layoutFlow', () => {
  const topology: FlowTopology = {
    nodes: [
      node('c', 'CONSUMER'),
      node('d2', 'DESTINATION', 'b-cola'),
      node('d1', 'DESTINATION', 'a-cola'),
      node('r', 'ROUTER'),
      node('p', 'PRODUCER'),
    ],
    edges: [],
  };

  it('puts producers, routers, destinations and consumers in four columns from left to right', () => {
    const { positions } = layoutFlow(topology);

    const xs = ['p', 'r', 'd1', 'c'].map((id) => positions.get(id)!.x);
    expect(xs).toEqual([...xs].sort((a, b) => a - b));
    expect(new Set(xs).size).toBe(4);
    expect(xs.every((x) => x > 0 && x < DIAGRAM_WIDTH)).toBe(true);
  });

  it('sorts the nodes of a column by name and gives each its own row', () => {
    const { positions } = layoutFlow(topology);

    expect(positions.get('d1')!.y).toBeLessThan(positions.get('d2')!.y);
    expect(positions.get('d1')!.x).toBe(positions.get('d2')!.x);
  });

  it('grows with the fullest column and is never empty', () => {
    expect(layoutFlow({ nodes: [], edges: [] }).height).toBeGreaterThan(0);
    const many = { nodes: Array.from({ length: 10 }, (_, i) => node(`d${i}`, 'DESTINATION')), edges: [] };
    expect(layoutFlow(many).height).toBeGreaterThan(layoutFlow(topology).height);
  });

  it('works for a system that is not RabbitMQ, with any ids and labels', () => {
    const kafka: FlowTopology = {
      nodes: [node('topic:pedidos/0', 'DESTINATION', 'pedidos-0'), node('group:facturas', 'CONSUMER', 'facturas')],
      edges: [],
    };

    expect(layoutFlow(kafka).positions.size).toBe(2);
  });
});

describe('topicMatches', () => {
  it.each([
    ['lab.temperatura', 'lab.temperatura', true],
    ['lab.*', 'lab.temperatura', true],
    ['lab.*', 'lab.temperatura.sala', false],
    ['lab.#', 'lab.temperatura.sala', true],
    ['lab.#', 'lab', true],
    ['#', 'a.b.c', true],
    ['*.temperatura', 'lab.temperatura', true],
    ['*', 'a.b', false],
    ['lab.#.sala', 'lab.a.b.sala', true],
    ['lab.#.sala', 'lab.a.b', false],
    ['otro', 'lab.temperatura', false],
    ['', '', true],
  ])('%s against %s is %s', (pattern, key, expected) => {
    expect(topicMatches(pattern, key)).toBe(expected);
  });
});

describe('matchesFilter', () => {
  const labels = new Map([
    ['r', 'sensores'],
    ['d1', 'cola.temperatura'],
    ['d2', 'cola.ruido'],
    ['p', 'robot-ur'],
  ]);

  it('lets everything pass without a filter', () => {
    expect(matchesFilter(event(), '', labels)).toBe(true);
    expect(matchesFilter(event(), '   ', labels)).toBe(true);
  });

  it('filters by routing key with wildcards', () => {
    expect(matchesFilter(event(), 'lab.*', labels)).toBe(true);
    expect(matchesFilter(event(), 'robot.#', labels)).toBe(false);
  });

  it('filters by the name of an exchange, a queue or a publisher, ignoring case', () => {
    expect(matchesFilter(event(), 'Sensores', labels)).toBe(true);
    expect(matchesFilter(event(), 'cola.ruido', labels)).toBe(true);
    expect(matchesFilter(event(), 'cola.*', labels)).toBe(true);
    expect(matchesFilter(event(), 'robot-ur', labels)).toBe(true);
    expect(matchesFilter(event(), 'inexistente', labels)).toBe(false);
  });

  it('uses the id when a node has no known label', () => {
    expect(matchesFilter(event({ router: 'x' }), 'x', new Map())).toBe(true);
  });
});

describe('hopsOf', () => {
  const known = new Set(['p', 'r', 'd1', 'd2', 'c']);

  it('sends a publication from the publisher to the router and then to each destination', () => {
    expect(hopsOf(event(), known)).toEqual([
      { from: 'p', to: 'r', stage: 0 },
      { from: 'r', to: 'd1', stage: 1 },
      { from: 'r', to: 'd2', stage: 1 },
    ]);
  });

  it('starts at the router when the publisher is unknown', () => {
    expect(hopsOf(event({ producer: null }), known)).toEqual([
      { from: 'r', to: 'd1', stage: 0 },
      { from: 'r', to: 'd2', stage: 0 },
    ]);
  });

  it('stops at the router for an unrouted publication', () => {
    expect(hopsOf(event({ destinations: [], unrouted: true }), known)).toEqual([{ from: 'p', to: 'r', stage: 0 }]);
  });

  it('sends a delivery from the queue to its consumer', () => {
    expect(hopsOf(event({ type: 'DELIVER', producer: null, destinations: ['d1'], consumer: 'c' }), known)).toEqual([
      { from: 'd1', to: 'c', stage: 0 },
    ]);
  });

  it('skips the hops of nodes that are not in the diagram', () => {
    expect(hopsOf(event(), new Set(['p', 'r', 'd1']))).toEqual([
      { from: 'p', to: 'r', stage: 0 },
      { from: 'r', to: 'd1', stage: 1 },
    ]);
    expect(hopsOf(event({ type: 'DELIVER', destinations: ['d1'], consumer: null }), known)).toEqual([]);
  });
});

describe('labelsOf', () => {
  it('maps ids to labels', () => {
    expect(labelsOf({ nodes: [node('a', 'ROUTER', 'Alfa')], edges: [] }).get('a')).toBe('Alfa');
  });
});

describe('averageRates', () => {
  const tick = (published: Record<string, number>): FlowTick => ({ time: 't', published, delivered: {}, ready: {}, omitted: 0 });
  const published = (t: FlowTick) => t.published;

  it('averages the last 5 seconds, so a slow emitter does not show 0 between its messages', () => {
    const ticks = [tick({ a: 1 }), tick({}), tick({}), tick({}), tick({})];

    expect(averageRates(ticks, published)).toEqual({ a: 0.2 });
  });

  it('ignores what is older than the window', () => {
    const ticks = [tick({ a: 100 }), tick({ a: 1 }), tick({ a: 1 }), tick({ a: 1 }), tick({ a: 1 }), tick({ a: 1 })];

    expect(averageRates(ticks, published)).toEqual({ a: 1 });
  });

  it('divides by the seconds it has when there are fewer than the window', () => {
    expect(averageRates([tick({ a: 4 }), tick({ a: 2 })], published)).toEqual({ a: 3 });
  });

  it('is empty without ticks', () => {
    expect(averageRates([], published)).toEqual({});
  });
});
