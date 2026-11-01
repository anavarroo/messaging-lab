import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { MAX_EVENTS, MAX_TICKS, RECONNECT_MS, FlowStore } from './flow-store';
import { FLOW_SOURCE, FlowEvent, FlowMessage, FlowSource, FlowTick } from './flow-model';

const event = (id: number): FlowEvent => ({
  id,
  time: '2026-10-30T10:00:00Z',
  type: 'PUBLISH',
  destinations: [],
  sizeBytes: 1,
  redelivered: false,
  unrouted: true,
});

const tick = (second: number, omitted = 0): FlowTick => ({
  time: `2026-10-30T10:00:${String(second).padStart(2, '0')}Z`,
  published: {},
  delivered: {},
  ready: {},
  omitted,
});

describe('FlowStore', () => {
  let streams: Subject<FlowMessage>[];
  let opened: number;
  let recent: () => Observable<FlowEvent[]>;
  let store: FlowStore;

  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    opened = 0;
    recent = () => of([]);
    const source: FlowSource = {
      recent: () => recent(),
      stream: () => {
        opened++;
        const stream = new Subject<FlowMessage>();
        streams.push(stream);
        return stream;
      },
    };
    TestBed.configureTestingModule({ providers: [FlowStore, { provide: FLOW_SOURCE, useValue: source }] });
    store = TestBed.inject(FlowStore);
  });

  afterEach(() => vi.useRealTimers());

  const current = () => streams[streams.length - 1];

  it('starts connecting and opens once, however many times it is started', () => {
    expect(store.connection()).toBe('connecting');
    store.start();
    store.start();

    expect(opened).toBe(1);
  });

  it('shows the status, the topology and the events the stream delivers', () => {
    store.start();
    current().next({ kind: 'status', status: { state: 'ACTIVE' } });
    current().next({ kind: 'topology', topology: { nodes: [{ id: 'r', kind: 'ROUTER', label: 'Router' }], edges: [] } });
    current().next({ kind: 'event', event: event(1) });
    current().next({ kind: 'event', event: event(2) });

    expect(store.connection()).toBe('open');
    expect(store.status()?.state).toBe('ACTIVE');
    expect(store.labels().get('r')).toBe('Router');
    expect(store.events().map((e) => e.id)).toEqual([2, 1]);
  });

  it('announces every new event to whoever animates them', () => {
    const arrived: number[] = [];
    store.arrivals.subscribe((e) => arrived.push(e.id));
    store.start();

    current().next({ kind: 'event', event: event(5) });

    expect(arrived).toEqual([5]);
  });

  it('puts the recent history under the live events without duplicating them', () => {
    recent = () => of([event(3), event(2), event(1)]);
    store.start();
    current().next({ kind: 'event', event: event(4) });
    current().next({ kind: 'event', event: event(3) });

    expect(store.events().map((e) => e.id)).toEqual([4, 3, 2, 1]);
  });

  it('opens even if the history cannot be read', () => {
    recent = () => throwError(() => new Error('fallo'));
    store.start();
    current().next({ kind: 'event', event: event(1) });

    expect(store.events()).toHaveLength(1);
  });

  it('keeps at most 1000 events and 120 ticks', () => {
    store.start();
    for (let i = 1; i <= MAX_EVENTS + 50; i++) {
      current().next({ kind: 'event', event: event(i) });
    }
    for (let i = 0; i < MAX_TICKS + 10; i++) {
      current().next({ kind: 'tick', tick: { ...tick(0), time: `2026-10-30T11:${String(Math.floor(i / 60)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}Z` } });
    }

    expect(store.events()).toHaveLength(MAX_EVENTS);
    expect(store.events()[0].id).toBe(MAX_EVENTS + 50);
    expect(store.ticks()).toHaveLength(MAX_TICKS);
  });

  it('reports the events omitted in the last second', () => {
    store.start();
    current().next({ kind: 'tick', tick: tick(1, 0) });
    current().next({ kind: 'tick', tick: tick(2, 450) });

    expect(store.omitted()).toBe(450);
  });

  it('clears the log without touching the charts', () => {
    store.start();
    current().next({ kind: 'event', event: event(1) });
    current().next({ kind: 'tick', tick: tick(1) });

    store.clearEvents();

    expect(store.events()).toEqual([]);
    expect(store.ticks()).toHaveLength(1);
  });

  describe('when the connection is cut', () => {
    it('says so, keeps what it showed and reconnects by itself', () => {
      store.start();
      current().next({ kind: 'status', status: { state: 'ACTIVE' } });
      current().next({ kind: 'event', event: event(1) });

      current().error(new Error('corte'));
      expect(store.connection()).toBe('lost');
      expect(store.events()).toHaveLength(1);
      expect(store.status()?.state).toBe('ACTIVE');
      expect(opened).toBe(1);

      vi.advanceTimersByTime(RECONNECT_MS);
      expect(opened).toBe(2);
      current().next({ kind: 'status', status: { state: 'ACTIVE' } });
      expect(store.connection()).toBe('open');
    });

    it('ignores the ticks and events the server sends again after reconnecting', () => {
      store.start();
      current().next({ kind: 'event', event: event(1) });
      current().next({ kind: 'tick', tick: tick(1) });
      current().next({ kind: 'tick', tick: tick(2) });
      current().error(new Error('corte'));
      vi.advanceTimersByTime(RECONNECT_MS);

      current().next({ kind: 'tick', tick: tick(1) });
      current().next({ kind: 'tick', tick: tick(2) });
      current().next({ kind: 'tick', tick: tick(3) });
      current().next({ kind: 'event', event: event(1) });
      current().next({ kind: 'event', event: event(2) });

      expect(store.ticks()).toHaveLength(3);
      expect(store.events().map((e) => e.id)).toEqual([2, 1]);
    });

    it('keeps trying while the server is down', () => {
      store.start();
      current().error(new Error('corte'));
      vi.advanceTimersByTime(RECONNECT_MS);
      current().error(new Error('sigue caído'));
      vi.advanceTimersByTime(RECONNECT_MS);

      expect(opened).toBe(3);
      expect(store.connection()).toBe('lost');
    });
  });

  it('releases the connection when it is destroyed', () => {
    store.start();

    TestBed.resetTestingModule();

    expect(current().observed).toBe(false);
  });
});
