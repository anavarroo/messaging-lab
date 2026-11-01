import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FlowNode, FlowTick, FlowTopology } from '../flow-model';
import { DEFAULT_SERIES, FlowCharts } from './flow-charts';

const node = (id: string, kind: FlowNode['kind'], label = id): FlowNode => ({ id, kind, label });
const tick = (second: number, parts: Partial<FlowTick> = {}): FlowTick => ({
  time: `2026-10-30T10:00:${String(second).padStart(2, '0')}Z`,
  published: {},
  delivered: {},
  ready: {},
  omitted: 0,
  ...parts,
});

describe('FlowCharts', () => {
  const topology: FlowTopology = {
    nodes: [node('p', 'PRODUCER', 'robot'), node('r', 'ROUTER', 'sensores'), node('d', 'DESTINATION', 'cola-a'), node('c', 'CONSUMER')],
    edges: [],
  };
  let fixture: ComponentFixture<FlowCharts>;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [FlowCharts] }).compileComponents();
    fixture = TestBed.createComponent(FlowCharts);
    root = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('topology', topology);
    fixture.componentRef.setInput('ticks', []);
    fixture.detectChanges();
  });

  const set = (ticks: FlowTick[], next: FlowTopology = topology) => {
    fixture.componentRef.setInput('topology', next);
    fixture.componentRef.setInput('ticks', ticks);
    fixture.detectChanges();
  };
  const legends = (chart: number) =>
    Array.from(root.querySelectorAll('app-line-chart')[chart].querySelectorAll('.legend li')).map((li) => li.textContent?.trim());
  const checkboxes = () => Array.from(root.querySelectorAll<HTMLInputElement>('.selection input'));

  it('has the two charts with their titles', () => {
    expect(Array.from(root.querySelectorAll('figcaption')).map((c) => c.textContent)).toEqual(['Mensajes por segundo', 'Mensajes listos']);
  });

  it('offers only the routers and destinations that have had activity, never producers or consumers', () => {
    expect(checkboxes()).toHaveLength(0);

    set([tick(1, { published: { r: 1, d: 1, p: 1 }, delivered: { c: 1 } })]);

    expect(checkboxes().map((c) => c.parentElement?.textContent?.trim())).toEqual(['cola-a', 'sensores']);
  });

  it('draws published and delivered series of the nodes that have activity, and the ready messages of the destinations', () => {
    set([
      tick(1, { published: { r: 5, d: 5 }, delivered: { d: 2 }, ready: { d: 30 } }),
      tick(2, { published: { r: 6, d: 6 }, delivered: { d: 3 }, ready: { d: 33 } }),
    ]);

    expect(legends(0)).toEqual(['cola-a · publicados', 'cola-a · entregados', 'sensores · publicados', 'sensores · entregados']);
    expect(legends(1)).toEqual(['cola-a']);
    expect(checkboxes().every((c) => c.checked)).toBe(true);
  });

  it('does not even offer the nodes that have had no activity', () => {
    set([tick(1, { published: { r: 5 } })]);

    expect(checkboxes().map((c) => c.parentElement?.textContent?.trim())).toEqual(['sensores']);
    expect(checkboxes().map((c) => c.checked)).toEqual([true]);
  });

  it('chooses by itself only the most active nodes', () => {
    const nodes = Array.from({ length: DEFAULT_SERIES + 2 }, (_, i) => node(`d${i}`, 'DESTINATION', `cola-${i}`));
    const ready = Object.fromEntries(nodes.map((n, i) => [n.id, i + 1]));

    set([tick(1, { ready })], { nodes, edges: [] });

    expect(legends(1)).toEqual(['cola-2', 'cola-3', 'cola-4', 'cola-5'].map((name) => name));
  });

  it('lets the user choose what to measure and respects the choice', () => {
    set([tick(1, { published: { r: 5, d: 5 }, ready: { d: 3 } })]);

    checkboxes()[0].click();
    fixture.detectChanges();
    expect(legends(0)).toEqual(['sensores · publicados', 'sensores · entregados']);
    expect(legends(1)).toEqual([]);

    set([tick(1, { published: { r: 5, d: 5 }, ready: { d: 3 } }), tick(2, { published: { r: 5, d: 99 }, ready: { d: 3 } })]);
    expect(checkboxes().map((c) => c.checked)).toEqual([false, true]);

    checkboxes()[0].click();
    fixture.detectChanges();
    expect(legends(1)).toEqual(['cola-a']);
  });

  it('shows how a queue grows when something publishes faster than it is consumed', () => {
    const ticks = Array.from({ length: 5 }, (_, i) => tick(i, { published: { d: 50 }, delivered: { d: 5 }, ready: { d: (i + 1) * 45 } }));

    set(ticks);

    const table = root.querySelectorAll('app-line-chart')[1].querySelector('tbody tr')!;
    expect(Array.from(table.children).map((c) => c.textContent?.trim())).toEqual(['cola-a', '225', '225', '135']);
  });

  it('says there is no data yet before the first second', () => {
    expect(root.querySelectorAll('.empty')).toHaveLength(2);
  });
});
