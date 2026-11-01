import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SimulatedConsumer, SimulatedConsumerRequest } from './generator-api';
import { ConsumerActionRequest, SimulatedConsumers } from './simulated-consumers';

const consumer = (id: string, extra: Partial<SimulatedConsumer> = {}): SimulatedConsumer => ({
  id,
  name: `consumidor-${id}`,
  queue: 'cocina',
  processing: { mode: 'FIXED', fixedMs: 100 },
  failurePercent: 10,
  onFailure: 'REQUEUE',
  status: 'STOPPED',
  processed: 0,
  failed: 0,
  ratePerSecond: 0,
  ...extra,
});

@Component({
  imports: [SimulatedConsumers],
  template: `
    <app-simulated-consumers
      [consumers]="consumers()"
      [queues]="queues"
      [busyIds]="busy()"
      [submitError]="error()"
      (created)="created.push($event)"
      (requested)="requests.push($event)"
      (removed)="removed.push($event)"
    />
  `,
})
class Host {
  consumers = signal<SimulatedConsumer[] | null>([]);
  busy = signal<ReadonlySet<string>>(new Set());
  error = signal<string | null>(null);
  queues = [{ name: 'cocina', ready: 0, unacknowledged: 0, total: 0 }];
  created: SimulatedConsumerRequest[] = [];
  requests: ConsumerActionRequest[] = [];
  removed: SimulatedConsumer[] = [];
  section = viewChild.required(SimulatedConsumers);
}

describe('SimulatedConsumers', () => {
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

  const set = (selector: string, value: string) => {
    const field = root.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
    field.value = value;
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  };
  const submit = () => {
    root.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const button = (scope: ParentNode, label: string) =>
    Array.from(scope.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement | undefined;
  const errors = () => Array.from(root.querySelectorAll('.field-error')).map((e) => e.textContent?.trim()).filter(Boolean);

  describe('creating', () => {
    it('submits a consumer with a fixed time and the failure behaviour', () => {
      set('#consumer-name', 'cocinero');
      set('#consumer-queue', 'cocina');
      set('#consumer-fixed', '250');
      set('#consumer-failure', '20');
      set('#consumer-on-failure', 'REJECT');

      submit();

      expect(host.created).toEqual([
        { name: 'cocinero', queue: 'cocina', processing: { mode: 'FIXED', fixedMs: 250 }, failurePercent: 20, onFailure: 'REJECT' },
      ]);
    });

    it('submits a random processing range', () => {
      set('#consumer-name', 'lento');
      set('#consumer-queue', 'cocina');
      root.querySelector<HTMLInputElement>('input[value=RANGE]')!.click();
      fixture.detectChanges();
      set('#consumer-min', '50');
      set('#consumer-max', '500');

      submit();

      expect(host.created[0].processing).toEqual({ mode: 'RANGE', minMs: 50, maxMs: 500 });
      expect(host.created[0]).toMatchObject({ failurePercent: 0, onFailure: 'REQUEUE' });
    });

    it('asks for everything that is missing and invalid without submitting', () => {
      set('#consumer-fixed', '1');
      set('#consumer-failure', '101');

      submit();

      expect(errors()).toEqual([
        'Escribe un nombre para el consumidor.',
        'Elige la cola de la que consumir.',
        'Los intervalos deben estar entre 2 ms y 86400000 ms (un día).',
        'El porcentaje de fallos debe ser un número entero entre 0 y 100.',
      ]);
      expect(host.created).toEqual([]);
    });

    it('shows the error from the server and clears the form on reset', () => {
      host.error.set('Ya existe un consumidor con ese nombre.');
      fixture.detectChanges();
      expect(root.querySelector('.error')?.textContent).toContain('Ya existe un consumidor');

      set('#consumer-name', 'x');
      host.section().reset();
      fixture.detectChanges();
      expect(root.querySelector<HTMLInputElement>('#consumer-name')!.value).toBe('');
    });
  });

  describe('list', () => {
    it('says so when there are none and while loading', () => {
      expect(root.querySelector('.empty')?.textContent).toContain('Todavía no hay consumidores simulados.');

      host.consumers.set(null);
      fixture.detectChanges();
      expect(root.querySelector('.loading')).not.toBeNull();
    });

    it('shows state and counters', () => {
      host.consumers.set([consumer('1', { status: 'RUNNING', processed: 40, failed: 4, ratePerSecond: 9.5 })]);
      fixture.detectChanges();

      const item = root.querySelector('li.consumer')!;
      expect(item.querySelector('.badge')?.textContent?.trim()).toBe('En marcha');
      expect(item.querySelector('.where')?.textContent).toContain('Cola «cocina» · cada 100 ms · 10 % de fallos');
      expect(item.textContent).toContain('40');
      expect(item.textContent).toContain('9,5 por segundo');
    });

    it('offers Iniciar when stopped and Parar when running', () => {
      host.consumers.set([consumer('1'), consumer('2', { status: 'RUNNING' })]);
      fixture.detectChanges();
      const [stopped, running] = Array.from(root.querySelectorAll('li.consumer'));

      expect(button(stopped, 'Iniciar')).toBeDefined();
      expect(button(stopped, 'Parar')).toBeUndefined();
      expect(button(running, 'Parar')).toBeDefined();

      button(stopped, 'Iniciar')!.click();
      button(running, 'Parar')!.click();
      expect(host.requests.map((r) => [r.consumer.id, r.action])).toEqual([['1', 'START'], ['2', 'STOP']]);
    });

    it('asks to delete (the removal of consumers: alta y baja) and blocks the busy ones', () => {
      host.consumers.set([consumer('1'), consumer('2')]);
      host.busy.set(new Set(['2']));
      fixture.detectChanges();
      const [free, busy] = Array.from(root.querySelectorAll('li.consumer'));

      button(free, 'Eliminar')!.click();

      expect(host.removed.map((c) => c.id)).toEqual(['1']);
      expect(button(busy, 'Eliminar')!.disabled).toBe(true);
      expect(button(busy, 'Iniciar')!.disabled).toBe(true);
    });

    it('shows why a consumer failed', () => {
      host.consumers.set([consumer('1', { status: 'FAILED', stopMessage: 'La cola «cocina» ya no existe.' })]);
      fixture.detectChanges();

      expect(root.querySelector('.stop-message')?.textContent).toContain('La cola «cocina» ya no existe.');
    });
  });
});
