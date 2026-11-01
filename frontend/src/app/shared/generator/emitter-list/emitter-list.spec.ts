import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmitterView } from '../emitter-view';
import { EmitterActionRequest, EmitterList } from './emitter-list';

describe('EmitterList', () => {
  const emitter = (id: string, extra: Partial<EmitterView> = {}): EmitterView => ({
    id,
    name: `emisor-${id}`,
    interval: { mode: 'FIXED', fixedMs: 500 },
    status: 'STOPPED',
    published: 0,
    unrouted: 0,
    ratePerSecond: 0,
    ...extra,
  });

  let fixture: ComponentFixture<EmitterList>;
  let page: HTMLElement;
  let requests: EmitterActionRequest[];
  let removals: EmitterView[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [EmitterList] }).compileComponents();
    fixture = TestBed.createComponent(EmitterList);
    page = fixture.nativeElement as HTMLElement;
    requests = [];
    removals = [];
    fixture.componentInstance.requested.subscribe((request) => requests.push(request));
    fixture.componentInstance.removed.subscribe((removed) => removals.push(removed));
    fixture.componentRef.setInput('destinationLabel', (e: EmitterView) => `destino de ${e.name}`);
  });

  function show(emitters: EmitterView[], busy: string[] = []) {
    fixture.componentRef.setInput('emitters', emitters);
    fixture.componentRef.setInput('busyIds', new Set(busy));
    fixture.detectChanges();
  }

  const items = () => Array.from(page.querySelectorAll<HTMLElement>('li.emitter'));
  const labels = (item: HTMLElement) =>
    Array.from(item.querySelectorAll('.actions button')).map((button) => button.textContent?.trim());
  const button = (item: HTMLElement, label: string) =>
    Array.from(item.querySelectorAll<HTMLButtonElement>('.actions button')).find((b) => b.textContent?.trim() === label)!;

  it('shows each emitter with its name, destination and frequency', () => {
    show([emitter('1', { interval: { mode: 'RANGE', minMs: 200, maxMs: 800 } })]);

    expect(items()).toHaveLength(1);
    expect(items()[0].querySelector('h5')?.textContent).toBe('emisor-1');
    expect(items()[0].querySelector('.where')?.textContent).toContain('destino de emisor-1');
    expect(items()[0].querySelector('.where')?.textContent).toContain('entre 200 ms y 800 ms');
  });

  it('describes a fixed frequency', () => {
    show([emitter('1')]);

    expect(items()[0].querySelector('.where')?.textContent).toContain('cada 500 ms');
  });

  it('shows the counters and the real rate (CA-1.8)', () => {
    show([emitter('1', { status: 'RUNNING', published: 120, unrouted: 7, ratePerSecond: 9.96 })]);

    const pairs = Array.from(items()[0].querySelectorAll('.counters dt')).map(
      (dt) => [dt.textContent?.trim(), dt.nextElementSibling?.textContent?.trim()] as const,
    );
    expect(pairs).toEqual([
      ['Publicados', '120'],
      ['Sin enrutar', '7'],
      ['Ritmo real', '10,0 por segundo'],
    ]);
  });

  it.each([
    ['STOPPED', 'Parado'],
    ['RUNNING', 'En marcha'],
    ['PAUSED', 'En pausa'],
    ['FINISHED', 'Terminado'],
    ['FAILED', 'Fallado'],
  ] as const)('shows the state %s as «%s»', (status, label) => {
    show([emitter('1', { status })]);

    expect(items()[0].querySelector('.badge')?.textContent?.trim()).toBe(label);
  });

  it('explains why an emitter stopped by itself', () => {
    show([emitter('1', { status: 'FAILED', stopMessage: 'Se ha parado porque el destino (la cola «x») ya no existe.' })]);

    expect(items()[0].querySelector('.stop-message')?.textContent).toContain('ya no existe');
    expect(items()[0].querySelector('.stop-message')?.getAttribute('role')).toBe('status');
  });

  it('shows no stop message when there is none', () => {
    show([emitter('1')]);

    expect(items()[0].querySelector('.stop-message')).toBeNull();
  });

  describe('the controls depend on the state (CA-1.4)', () => {
    it.each(['STOPPED', 'FINISHED', 'FAILED'] as const)('offers Start and Delete when it is %s', (status) => {
      show([emitter('1', { status })]);

      expect(labels(items()[0])).toEqual(['Iniciar', 'Eliminar']);
    });

    it('offers Pause, Stop and Delete when it is running', () => {
      show([emitter('1', { status: 'RUNNING' })]);

      expect(labels(items()[0])).toEqual(['Pausar', 'Parar', 'Eliminar']);
    });

    it('offers Resume, Stop and Delete when it is paused', () => {
      show([emitter('1', { status: 'PAUSED' })]);

      expect(labels(items()[0])).toEqual(['Reanudar', 'Parar', 'Eliminar']);
    });
  });

  it.each([
    ['STOPPED', 'Iniciar', 'START'],
    ['RUNNING', 'Pausar', 'PAUSE'],
    ['PAUSED', 'Reanudar', 'RESUME'],
    ['RUNNING', 'Parar', 'STOP'],
  ] as const)('a %s emitter asks for %s with the action %s', (status, label, action) => {
    const view = emitter('1', { status });
    show([view]);

    button(items()[0], label).click();

    expect(requests).toEqual([{ emitter: view, action }]);
  });

  it('asks to delete without deciding anything itself', () => {
    const view = emitter('1', { status: 'RUNNING' });
    show([view]);

    button(items()[0], 'Eliminar').click();

    expect(removals).toEqual([view]);
    expect(requests).toEqual([]);
  });

  it('blocks the buttons of an emitter with an operation in progress and only those (CA-11.5)', () => {
    show([emitter('1', { status: 'RUNNING' }), emitter('2', { status: 'RUNNING' })], ['1']);

    const disabled = (item: HTMLElement) =>
      Array.from(item.querySelectorAll<HTMLButtonElement>('.actions button')).map((b) => b.disabled);
    expect(disabled(items()[0])).toEqual([true, true, true]);
    expect(disabled(items()[1])).toEqual([false, false, false]);
  });

  it('labels each control group and delete button with the emitter they affect', () => {
    show([emitter('1')]);

    expect(items()[0].getAttribute('aria-label')).toBe('Emisor emisor-1');
    expect(items()[0].querySelector('.actions')?.getAttribute('aria-label')).toBe('Controles de emisor-1');
    expect(button(items()[0], 'Eliminar').getAttribute('aria-label')).toBe('Eliminar el emisor emisor-1');
  });

  it('uses native buttons that are operable with the keyboard', () => {
    show([emitter('1', { status: 'RUNNING' })]);

    items()[0].querySelectorAll<HTMLButtonElement>('.actions button').forEach((b) => {
      expect(b.tagName).toBe('BUTTON');
      expect(b.type).toBe('button');
    });
  });

  it('shows several emitters in order and keeps each one’s own state', () => {
    show([emitter('1', { status: 'RUNNING' }), emitter('2', { status: 'PAUSED' }), emitter('3')]);

    expect(items().map((item) => item.querySelector('.badge')?.textContent?.trim())).toEqual(['En marcha', 'En pausa', 'Parado']);
  });

  it('shows nothing for an empty list', () => {
    show([]);

    expect(items()).toHaveLength(0);
  });
});
