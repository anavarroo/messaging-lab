import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FlowEvent } from '../flow-model';
import { FlowLog, VISIBLE_ROWS } from './flow-log';

const event = (id: number, extra: Partial<FlowEvent> = {}): FlowEvent => ({
  id,
  time: '2026-10-30T10:00:00.123Z',
  type: 'PUBLISH',
  producer: 'p',
  router: 'r',
  destinations: ['d'],
  key: `clave.${id}`,
  sizeBytes: 10 + id,
  redelivered: false,
  unrouted: false,
  body: `cuerpo ${id}`,
  properties: { content_type: 'application/json' },
  ...extra,
});

@Component({
  imports: [FlowLog],
  template: `<app-flow-log [events]="events()" [labels]="labels" [omitted]="omitted()" (cleared)="cleared = cleared + 1" />`,
})
class Host {
  events = signal<FlowEvent[]>([]);
  omitted = signal(0);
  labels = new Map([['p', 'robot'], ['r', 'sensores'], ['d', 'cola-a'], ['c', 'cocinero']]);
  cleared = 0;
  log = viewChild.required(FlowLog);
}

describe('FlowLog', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const rows = () => Array.from(root.querySelectorAll('tbody tr'));
  const button = (label: string) =>
    Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
  const update = (events: FlowEvent[]) => {
    host.events.set(events);
    fixture.detectChanges();
  };

  it('says nothing has passed yet when there are no events', () => {
    expect(root.querySelector('.empty')?.textContent).toContain('Aún no ha pasado ningún mensaje.');
    expect(rows()).toHaveLength(0);
  });

  it('lists the events most recent first with their columns', () => {
    update([event(2), event(1)]);

    expect(rows()).toHaveLength(2);
    const cells = Array.from(rows()[0].querySelectorAll('td')).map((td) => td.textContent?.replace(/\s+/g, ' ').trim());
    expect(cells[1]).toBe('Publicación');
    expect(cells[2]).toBe('sensores → cola-a');
    expect(cells[3]).toBe('clave.2');
    expect(cells[4]).toBe('12');
  });

  it('shows the route of a delivery from the queue to its consumer', () => {
    update([event(1, { type: 'DELIVER', producer: null, consumer: 'c', redelivered: true })]);

    expect(rows()[0].textContent).toContain('cola-a → cocinero');
    expect(rows()[0].textContent).toContain('Entrega');
    expect(rows()[0].textContent).toContain('reentrega');
  });

  it('marks an unrouted publication', () => {
    update([event(1, { destinations: [], unrouted: true })]);

    expect(rows()[0].querySelector('.unrouted-badge')?.textContent).toContain('sin enrutar');
    expect(rows()[0].textContent).toContain('sensores → sin destino');
  });

  it('draws at most 200 rows and says how many are hidden', () => {
    update(Array.from({ length: VISIBLE_ROWS + 30 }, (_, i) => event(1000 - i)));

    expect(rows()).toHaveLength(VISIBLE_ROWS);
    expect(root.querySelector('.help')?.textContent).toContain('Se muestran los 200 más recientes de 230');
  });

  describe('pause', () => {
    it('freezes the list, counts what arrives meanwhile and shows it when resumed', () => {
      update([event(1)]);

      button('Pausar registro').click();
      fixture.detectChanges();
      update([event(3), event(2), event(1)]);

      expect(rows()).toHaveLength(1);
      expect(root.querySelector('.notice')?.textContent).toContain('Registro en pausa');
      expect(root.querySelector('.notice')?.textContent).toContain('Han llegado 2 eventos más');
      expect(button('Reanudar registro').getAttribute('aria-pressed')).toBe('true');

      button('Reanudar registro').click();
      fixture.detectChanges();

      expect(rows()).toHaveLength(3);
      expect(root.querySelector('.notice')).toBeNull();
    });
  });

  describe('clear', () => {
    it('asks the owner of the events to clear them and closes the detail', () => {
      update([event(1)]);
      button('Ver').click();
      fixture.detectChanges();
      expect(root.querySelector('.detail')).not.toBeNull();

      button('Limpiar').click();
      fixture.detectChanges();

      expect(host.cleared).toBe(1);
      expect(root.querySelector('.detail')).toBeNull();
    });
  });

  describe('detail', () => {
    it('shows the body, the size and the properties of the chosen event', () => {
      update([event(2), event(1)]);

      (rows()[1].querySelector('button') as HTMLButtonElement).click();
      fixture.detectChanges();

      const detail = root.querySelector('.detail')!;
      expect(detail.textContent).toContain('Publicado por robot');
      expect(detail.querySelector('.body')?.textContent).toBe('cuerpo 1');
      expect(detail.querySelector('.properties')?.textContent).toContain('"content_type": "application/json"');
      expect(rows()[1].getAttribute('class')).toContain('selected');
    });

    it('hides it by pressing the same button again', () => {
      update([event(1)]);
      button('Ver').click();
      fixture.detectChanges();

      button('Ocultar').click();
      fixture.detectChanges();

      expect(root.querySelector('.detail')).toBeNull();
    });

    it('shows a body with HTML as plain text, never as markup', () => {
      update([event(1, { body: '<img src=x onerror=alert(1)><b>negrita</b>' })]);
      button('Ver').click();
      fixture.detectChanges();

      const body = root.querySelector('.body')!;
      expect(body.textContent).toBe('<img src=x onerror=alert(1)><b>negrita</b>');
      expect(body.querySelector('img, b')).toBeNull();
    });

    it('says when the body was truncated', () => {
      update([event(1, { bodyTruncated: true })]);
      button('Ver').click();
      fixture.detectChanges();

      expect(root.querySelector('.detail')?.textContent).toContain('truncado a 4 KB');
    });
  });

  it('warns that events are being omitted while keeping the counters exact', () => {
    host.omitted.set(450);
    update([event(1)]);

    expect(root.querySelector('.omitted')?.textContent).toContain('Se omiten 450 eventos por segundo');
  });
});
