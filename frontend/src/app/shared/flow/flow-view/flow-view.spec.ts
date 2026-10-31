import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { HOP_MS } from '../flow-animator';
import { FLOW_SOURCE, FlowEvent, FlowMessage, FlowSource, FlowTick, FlowTopology, FlowVocabulary } from '../flow-model';
import { NOW } from '../flow-store';
import { ANIMATION_FRAME, FlowView, PREFERS_REDUCED_MOTION } from './flow-view';

/*
 * La fuente de estas pruebas NO es de RabbitMQ: habla de topics, particiones y grupos, con otros ids y otras
 * palabras. Demuestra que el visualizador es reutilizable por cualquier laboratorio.
 */
const KAFKA_WORDS: FlowVocabulary = {
  producer: 'Productor',
  router: 'Topic',
  destination: 'Partición',
  consumer: 'Grupo',
  key: 'Clave',
};

const topology: FlowTopology = {
  nodes: [
    { id: 'app:facturas', kind: 'PRODUCER', label: 'facturas-app' },
    { id: 'topic:pedidos', kind: 'ROUTER', label: 'pedidos' },
    { id: 'topic:pedidos/0', kind: 'DESTINATION', label: 'pedidos-0' },
    { id: 'group:contabilidad', kind: 'CONSUMER', label: 'contabilidad' },
  ],
  edges: [
    { from: 'app:facturas', to: 'topic:pedidos' },
    { from: 'topic:pedidos', to: 'topic:pedidos/0' },
    { from: 'topic:pedidos/0', to: 'group:contabilidad' },
  ],
};

const publication = (id: number, extra: Partial<FlowEvent> = {}): FlowEvent => ({
  id,
  time: '2026-10-30T10:00:00.000Z',
  type: 'PUBLISH',
  producer: 'app:facturas',
  router: 'topic:pedidos',
  destinations: ['topic:pedidos/0'],
  key: 'cliente.42',
  sizeBytes: 8,
  redelivered: false,
  unrouted: false,
  body: 'factura',
  ...extra,
});

const tick = (second: number, extra: Partial<FlowTick> = {}): FlowTick => ({
  time: `2026-10-30T10:00:${String(second).padStart(2, '0')}Z`,
  published: {},
  delivered: {},
  ready: {},
  omitted: 0,
  ...extra,
});

describe('FlowView with a source that is not RabbitMQ', () => {
  let stream: Subject<FlowMessage>;
  let pendingFrame: ((now: number) => void) | null;
  let now: number;
  let reduced: boolean;
  let clock: number;
  let fixture: ComponentFixture<FlowView>;
  let root: HTMLElement;

  beforeEach(() => {
    stream = new Subject<FlowMessage>();
    pendingFrame = null;
    now = 1_000;
    reduced = false;
    clock = Date.parse('2026-10-30T10:00:30Z');
  });

  async function create() {
    const source: FlowSource = { recent: () => of([]), stream: () => stream };
    await TestBed.configureTestingModule({
      imports: [FlowView],
      providers: [
        { provide: FLOW_SOURCE, useValue: source },
        { provide: ANIMATION_FRAME, useValue: { request: (cb: (now: number) => void) => ((pendingFrame = cb), 1), cancel: () => (pendingFrame = null) } },
        { provide: PREFERS_REDUCED_MOTION, useValue: () => reduced },
        { provide: NOW, useValue: () => clock },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(FlowView);
    fixture.componentRef.setInput('vocabulary', KAFKA_WORDS);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const send = (message: FlowMessage) => {
    stream.next(message);
    fixture.detectChanges();
  };
  const connect = (state: 'ACTIVE' | 'DISABLED' | 'UNAVAILABLE' = 'ACTIVE', hint?: string) => {
    send({ kind: 'status', status: { state, hint } });
    send({ kind: 'topology', topology });
  };
  /** Avanza la animación `ms` milisegundos de reloj, en un único fotograma. */
  const frame = (ms: number) => {
    now += ms;
    const callback = pendingFrame!;
    pendingFrame = null;
    callback(now);
    fixture.detectChanges();
  };
  /** Avanza la animación `ms` milisegundos con fotogramas de 50 ms, mientras pida más. */
  const run = (ms: number) => {
    for (let elapsed = 0; elapsed < ms && pendingFrame; elapsed += 50) {
      frame(50);
    }
  };
  const dots = () => root.querySelectorAll('.dot');
  const logRows = () => root.querySelectorAll('app-flow-log tbody tr');
  const button = (label: string) => Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
  const type = (value: string) => {
    const input = root.querySelector<HTMLInputElement>('#flow-filter')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  describe('state of the source', () => {
    beforeEach(create);

    it('says it is connecting until the first message', () => {
      expect(root.textContent).toContain('Conectando con la fuente de datos…');
    });

    it('shows nothing alarming when the source is active, and asks for a first message', () => {
      connect('ACTIVE');

      expect(root.querySelector('.error')).toBeNull();
      expect(root.querySelector('app-flow-log .empty')?.textContent).toContain('Aún no ha pasado ningún mensaje.');
    });

    it('explains how to enable the source when it is disabled', () => {
      connect('DISABLED', 'Activa el trazado con: rabbitmqctl trace_on');

      expect(root.querySelector('.error')?.textContent).toContain('Activa el trazado con: rabbitmqctl trace_on');
      expect(root.querySelector('.error')?.getAttribute('role')).toBe('alert');
    });

    it('says when the system is unavailable', () => {
      connect('UNAVAILABLE', 'El sistema no está disponible.');

      expect(root.querySelector('.error')?.textContent).toContain('El sistema no está disponible.');
    });

    it('recovers by itself when the source becomes active again', () => {
      connect('DISABLED', 'desactivado');
      send({ kind: 'status', status: { state: 'ACTIVE' } });

      expect(root.querySelector('.error')).toBeNull();
    });

    it('tells the user when the connection is cut and keeps what was shown', () => {
      connect();
      send({ kind: 'event', event: publication(1) });

      stream.error(new Error('corte'));
      fixture.detectChanges();

      expect(root.querySelector('.error')?.textContent).toContain('Se ha cortado la conexión de datos');
      expect(logRows()).toHaveLength(1);
      expect(root.querySelectorAll('g.node')).toHaveLength(3);
    });
  });

  describe('with data', () => {
    beforeEach(async () => {
      await create();
      connect();
    });

    it('draws the topology of the source with its own words', () => {
      send({ kind: 'event', event: publication(1) });
      send({ kind: 'event', event: { ...publication(2), type: 'DELIVER', producer: null, consumer: 'group:contabilidad' } });
      const captions = Array.from(root.querySelectorAll('g.node .caption')).map((c) => c.textContent);

      expect(captions).toEqual(expect.arrayContaining(['Productor', 'Topic', 'Partición', 'Grupo']));
    });

    it('fills the log with the events and the charts with the ticks', () => {
      send({ kind: 'event', event: publication(1) });
      send({ kind: 'tick', tick: tick(1, { published: { 'topic:pedidos': 5 }, ready: { 'topic:pedidos/0': 12 } }) });

      expect(logRows()).toHaveLength(1);
      expect(logRows()[0].textContent).toContain('pedidos → pedidos-0');
      expect(root.querySelector('app-flow-diagram')?.textContent).toContain('12 listos');
      expect(root.querySelectorAll('app-line-chart')).toHaveLength(2);
    });

    it('clears the log without clearing the charts', () => {
      send({ kind: 'event', event: publication(1) });
      send({ kind: 'tick', tick: tick(1, { published: { 'topic:pedidos': 5 } }) });

      button('Limpiar').click();
      fixture.detectChanges();

      expect(logRows()).toHaveLength(0);
      expect(root.querySelectorAll('app-line-chart tbody tr').length).toBeGreaterThan(0);
    });

    it('warns of the events omitted when there is too much traffic', () => {
      send({ kind: 'tick', tick: tick(1, { omitted: 450 }) });

      expect(root.querySelector('.omitted')?.textContent).toContain('Se omiten 450 eventos por segundo');
    });

    describe('animation', () => {
      it('moves a point along the path of each event', () => {
        send({ kind: 'event', event: publication(1) });
        frame(0);
        frame(HOP_MS / 2);

        expect(dots()).toHaveLength(1);
        expect(root.querySelector('circle.dot')).not.toBeNull();
      });

      it('moves on to the second hop and then stops asking for frames', () => {
        send({ kind: 'event', event: publication(1) });
        frame(0);
        run(HOP_MS + 10);
        expect(dots()).toHaveLength(1);

        run(HOP_MS + 10);

        expect(dots()).toHaveLength(0);
        expect(pendingFrame).toBeNull();
      });

      it('draws an unrouted publication as a square that does not go past the router', () => {
        send({ kind: 'event', event: publication(1, { destinations: [], unrouted: true }) });
        frame(0);
        frame(HOP_MS / 2);

        expect(root.querySelectorAll('rect.dot.unrouted')).toHaveLength(1);
        expect(root.querySelectorAll('circle.dot')).toHaveLength(0);
      });

      it('freezes when paused, keeps registering events, and resumes where it was', () => {
        send({ kind: 'event', event: publication(1) });
        frame(0);
        frame(HOP_MS / 2);
        const before = root.querySelector('circle.dot')!.getAttribute('cx');

        button('Pausar animación').click();
        fixture.detectChanges();
        send({ kind: 'event', event: publication(2) });

        expect(button('Reanudar animación').getAttribute('aria-pressed')).toBe('true');
        expect(root.querySelector('.animation-note')?.textContent).toContain('Animación en pausa');
        expect(logRows()).toHaveLength(2);
        expect(pendingFrame === null || dots().length === 1).toBe(true);

        button('Reanudar animación').click();
        fixture.detectChanges();
        frame(0);
        expect(root.querySelector('circle.dot')!.getAttribute('cx')).toBe(before);
        expect(dots()).toHaveLength(1);
      });

      it('slows the animation down to a tenth', () => {
        const select = root.querySelector<HTMLSelectElement>('#flow-speed')!;
        select.value = '0.1';
        select.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        send({ kind: 'event', event: publication(1) });
        frame(0);

        run(HOP_MS * 5);
        expect(dots().length).toBeGreaterThan(0);

        run(HOP_MS * 20);
        expect(dots()).toHaveLength(0);
        expect(pendingFrame).toBeNull();
      });

      it('does not let a long pause between frames make the animation jump', () => {
        send({ kind: 'event', event: publication(1) });
        frame(0);

        frame(60_000);

        expect(dots()).toHaveLength(1);
      });
    });

    describe('filter', () => {
      beforeEach(() => {
        send({ kind: 'event', event: publication(1, { key: 'cliente.42' }) });
        send({ kind: 'event', event: publication(2, { key: 'proveedor.7' }) });
      });

      it('keeps only the events that match, with topic wildcards', () => {
        type('cliente.*');

        expect(logRows()).toHaveLength(1);
        expect(logRows()[0].textContent).toContain('cliente.42');
      });

      it('also filters by the name of a node', () => {
        type('Contabilidad');
        expect(logRows()).toHaveLength(0);

        type('pedidos-0');
        expect(logRows()).toHaveLength(2);
      });

      it('shows everything again when cleared', () => {
        type('cliente.*');
        type('');

        expect(logRows()).toHaveLength(2);
      });

      it('does not animate the events that do not match', () => {
        run(HOP_MS * 3);
        expect(pendingFrame).toBeNull();
        type('proveedor.#');
        send({ kind: 'event', event: publication(3, { key: 'cliente.1' }) });

        expect(pendingFrame).toBeNull();
        send({ kind: 'event', event: publication(4, { key: 'proveedor.9' }) });
        expect(pendingFrame).not.toBeNull();
      });
    });
  });

  describe('only what has active data flow', () => {
    beforeEach(async () => {
      await create();
      connect();
    });

    const nodeNames = () => Array.from(root.querySelectorAll('g.node .label')).map((l) => l.textContent);
    const liveTick = (extra: Partial<FlowTick> = {}) =>
      tick(0, { time: new Date(clock).toISOString(), ...extra });

    it('draws nothing, and says so, while no message has passed', () => {
      expect(root.querySelectorAll('g.node')).toHaveLength(0);
      expect(root.querySelector('app-flow-diagram')).toBeNull();
      expect(root.querySelector('.empty')?.textContent).toContain('No hay flujo activo ahora mismo');
    });

    it('draws only the nodes a message has gone through, and the connections between them', () => {
      send({ kind: 'event', event: publication(1) });

      expect(nodeNames()).toEqual(['facturas-app', 'pedidos', 'pedidos-0']);
      expect(root.querySelectorAll('path.edge')).toHaveLength(2);
      expect(nodeNames()).not.toContain('contabilidad');
    });

    it('adds a consumer when a delivery reaches it', () => {
      send({ kind: 'event', event: publication(1) });
      send({ kind: 'event', event: { ...publication(2), type: 'DELIVER', producer: null, consumer: 'group:contabilidad' } });

      expect(nodeNames()).toContain('contabilidad');
      expect(root.querySelectorAll('path.edge')).toHaveLength(3);
    });

    it('also counts the nodes the per-second summary says are moving messages, even if no event was sampled', () => {
      send({ kind: 'tick', tick: liveTick({ published: { 'topic:pedidos': 3, 'topic:pedidos/0': 3 }, delivered: { 'group:contabilidad': 1 } }) });

      expect(nodeNames()).toEqual(['pedidos', 'pedidos-0', 'contabilidad']);
    });

    it('does not count a queue just because it has ready messages', () => {
      send({ kind: 'tick', tick: liveTick({ ready: { 'topic:pedidos/0': 500 } }) });

      expect(nodeNames()).toEqual([]);
    });

    it('does not count the old summaries the server resends when connecting', () => {
      send({ kind: 'tick', tick: tick(1, { published: { 'topic:pedidos': 9 } }) });

      expect(nodeNames()).toEqual([]);
    });

    it('removes what has had no messages for 10 seconds', () => {
      send({ kind: 'event', event: publication(1) });
      expect(nodeNames()).toHaveLength(3);

      clock += 9_000;
      send({ kind: 'tick', tick: liveTick() });
      expect(nodeNames()).toHaveLength(3);

      clock += 2_000;
      send({ kind: 'tick', tick: { ...liveTick(), time: new Date(clock).toISOString() } });

      expect(nodeNames()).toEqual([]);
      expect(root.querySelector('.empty')?.textContent).toContain('No hay flujo activo');
    });

    it('keeps a node while messages keep passing through it', () => {
      send({ kind: 'event', event: publication(1) });
      clock += 8_000;
      send({ kind: 'event', event: publication(2) });
      clock += 8_000;
      send({ kind: 'tick', tick: { ...liveTick(), time: new Date(clock).toISOString() } });

      expect(nodeNames()).toHaveLength(3);
    });

    it('still lists in the log and animates the events whose nodes just appeared', () => {
      send({ kind: 'event', event: publication(1) });
      frame(0);
      frame(HOP_MS / 2);

      expect(logRows()).toHaveLength(1);
      expect(dots()).toHaveLength(1);
    });
  });

  describe('reduced motion', () => {
    beforeEach(async () => {
      reduced = true;
      await create();
      connect();
    });

    it('creates no points, disables the controls and says where to look instead', () => {
      send({ kind: 'event', event: publication(1) });

      expect(pendingFrame).toBeNull();
      expect(dots()).toHaveLength(0);
      expect(logRows()).toHaveLength(1);
      expect(button('Pausar animación').disabled).toBe(true);
      expect(root.querySelector<HTMLSelectElement>('#flow-speed')!.disabled).toBe(true);
      expect(root.querySelector('.animation-note')?.textContent).toContain('no hay animación');
    });
  });

  it('stops asking for frames when it is destroyed', async () => {
    await create();
    connect();
    send({ kind: 'event', event: publication(1) });
    expect(pendingFrame).not.toBeNull();

    fixture.destroy();

    expect(pendingFrame).toBeNull();
  });
});
