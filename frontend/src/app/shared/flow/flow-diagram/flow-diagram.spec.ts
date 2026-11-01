import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Particle } from '../flow-animator';
import { FlowNode, FlowTopology } from '../flow-model';
import { FlowDiagram } from './flow-diagram';

const node = (id: string, kind: FlowNode['kind'], label = id, detail?: string): FlowNode => ({ id, kind, label, detail });

const topology: FlowTopology = {
  nodes: [
    node('p', 'PRODUCER', 'robot-ur'),
    node('r', 'ROUTER', 'sensores', 'topic'),
    node('d', 'DESTINATION', 'cola-a'),
    node('c', 'CONSUMER', 'cocinero'),
  ],
  edges: [
    { from: 'p', to: 'r' },
    { from: 'r', to: 'd', label: 'lab.*' },
    { from: 'd', to: 'c' },
    { from: 'x', to: 'd' },
  ],
};

describe('FlowDiagram', () => {
  let fixture: ComponentFixture<FlowDiagram>;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [FlowDiagram] }).compileComponents();
    fixture = TestBed.createComponent(FlowDiagram);
    root = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('topology', topology);
    fixture.detectChanges();
  });

  const set = (name: string, value: unknown) => {
    fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
  };
  const nodes = () => Array.from(root.querySelectorAll('g.node'));
  const nodeOf = (label: string) => nodes().find((g) => g.querySelector('.label')?.textContent === label)!;

  it('draws every node of the topology with its name and its kind', () => {
    expect(nodes()).toHaveLength(4);
    expect(nodeOf('sensores').querySelector('.caption')?.textContent).toBe('Enrutador · topic');
    expect(nodeOf('cola-a').querySelector('.caption')?.textContent).toBe('Destino');
    expect(nodeOf('cocinero').getAttribute('class')).toContain('consumer');
  });

  it('draws the connections between nodes it knows and skips the others', () => {
    expect(root.querySelectorAll('path.edge')).toHaveLength(3);
    expect(root.querySelector('path.edge title')?.textContent).toBe('lab.*');
  });

  it('places the nodes from left to right by kind', () => {
    const x = (label: string) => Number(/translate\(([\d.]+)/.exec(nodeOf(label).getAttribute('transform')!)![1]);

    expect(x('robot-ur')).toBeLessThan(x('sensores'));
    expect(x('sensores')).toBeLessThan(x('cola-a'));
    expect(x('cola-a')).toBeLessThan(x('cocinero'));
  });

  it('shows the current rate of each node and the ready messages of the destinations', () => {
    set('published', { r: 12, d: 12 });
    set('delivered', { d: 2.5, c: 2.5 });
    set('ready', { d: 480 });

    expect(nodeOf('sensores').querySelector('.numbers')?.textContent).toBe('12/s');
    expect(nodeOf('cola-a').querySelector('.numbers')?.textContent).toBe('14,5/s · 480 listos');
    expect(nodeOf('cocinero').querySelector('.numbers')?.textContent).toBe('2,5/s');
    expect(nodeOf('robot-ur').querySelector('.numbers')?.textContent).toBe('0/s');
  });

  it('does not show ready messages for nodes that are not destinations', () => {
    set('ready', { r: 5 });

    expect(nodeOf('sensores').querySelector('.numbers')?.textContent).not.toContain('listos');
  });

  it('updates when a new topology arrives', () => {
    set('topology', { nodes: [...topology.nodes, node('d2', 'DESTINATION', 'cola-b')], edges: [] });

    expect(nodes()).toHaveLength(5);
    expect(root.querySelectorAll('path.edge')).toHaveLength(0);
  });

  it('uses the vocabulary of the laboratory', () => {
    set('vocabulary', { producer: 'Productor', router: 'Exchange', destination: 'Cola', consumer: 'Consumidor', key: 'Routing key' });

    expect(nodeOf('sensores').querySelector('.caption')?.textContent).toBe('Exchange · topic');
    expect(nodeOf('cola-a').querySelector('.caption')?.textContent).toBe('Cola');
  });

  describe('moving points', () => {
    const particle = (extra: Partial<Particle> = {}): Particle => ({ key: 1, from: 'p', to: 'r', progress: 0.5, unrouted: false, ...extra });

    it('draws each point between its two nodes in proportion to its progress', () => {
      set('particles', [particle({ progress: 0 }), particle({ key: 2, progress: 1 }), particle({ key: 3, progress: 0.5 })]);

      const xs = Array.from(root.querySelectorAll('circle.dot')).map((c) => Number(c.getAttribute('cx')));
      expect(xs).toHaveLength(3);
      expect(xs[0]).toBeLessThan(xs[2]);
      expect(xs[2]).toBeLessThan(xs[1]);
    });

    it('draws the points of an unrouted publication as squares that fade out', () => {
      set('particles', [particle({ unrouted: true, progress: 0.9 })]);

      expect(root.querySelectorAll('circle.dot')).toHaveLength(0);
      const square = root.querySelector('rect.dot.unrouted')!;
      expect(Number(square.getAttribute('opacity'))).toBeLessThan(0.4);
    });

    it('skips a point whose nodes are not in the diagram', () => {
      set('particles', [particle({ to: 'desconocido' })]);

      expect(root.querySelectorAll('.dot')).toHaveLength(0);
    });
  });

  it('describes itself for assistive technology', () => {
    const svg = root.querySelector('svg')!;

    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toContain('1 publicador(es), 1 enrutador(s), 1 destino(s) y 1 consumidor(es)');
  });

  it('truncates very long names but keeps the whole name in its tooltip', () => {
    set('topology', { nodes: [node('d', 'DESTINATION', 'una-cola-con-un-nombre-larguisimo-de-verdad')], edges: [] });

    expect(root.querySelector('.label')?.textContent).toBe('una-cola-con-un-nombre-la…');
    expect(root.querySelector('g.node title')?.textContent).toContain('una-cola-con-un-nombre-larguisimo-de-verdad');
  });
});
