import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { ControlStatus, ControlledService, ControlledServiceName, ServiceControlApi } from './service-control-api';
import { RECHECK_MS, START_PATIENCE_MS, ServiceControls } from './service-controls';

@Component({
  imports: [ServiceControls],
  template: '<app-service-controls [service]="service()" [up]="up()" (recheck)="rechecks = rechecks + 1" />',
})
class Host {
  readonly service = signal<ControlledServiceName>('RABBITMQ');
  readonly up = signal(true);
  rechecks = 0;
}

describe('ServiceControls', () => {
  let status: ReturnType<typeof signal<ControlStatus | null>>;
  let actions: { service: string; action: string; answer: Subject<ControlledService> }[];
  let fixture: ComponentFixture<Host>;
  let root: HTMLElement;

  const running = (service: ControlledServiceName = 'RABBITMQ'): ControlStatus => ({
    available: true,
    services: [
      { service: 'RABBITMQ', state: service === 'RABBITMQ' ? 'RUNNING' : 'STOPPED' },
      { service: 'ASSISTANT', state: 'RUNNING' },
    ],
  });

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    status = signal<ControlStatus | null>(running());
    actions = [];
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        {
          provide: ServiceControlApi,
          useValue: {
            status,
            load: () => undefined,
            act: (service: string, action: string) => {
              const answer = new Subject<ControlledService>();
              actions.push({ service, action, answer });
              return answer;
            },
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const button = (label: string) =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label)!;
  const dialog = () => root.querySelector('dialog') as HTMLDialogElement;
  const progress = () => root.querySelector('.progress')?.textContent?.trim();

  it('offers to stop a running service and not to start it', () => {
    expect(button('Levantar').disabled).toBe(true);
    expect(button('Parar').disabled).toBe(false);
  });

  it('asks before stopping, explaining the consequences, and does nothing if cancelled', async () => {
    button('Parar').click();
    await settle();
    expect(dialog().hasAttribute('open')).toBe(true);
    expect(dialog().textContent).toContain('se detienen los consumidores, el generador y el flujo');
    expect(dialog().textContent).toContain('Los mensajes guardados se conservan');

    button('Cancelar').click();
    await settle();
    expect(actions).toEqual([]);
  });

  it('the assistant warning says the chat stops and the memory is freed', async () => {
    fixture.componentInstance.service.set('ASSISTANT');
    await settle();
    button('Parar').click();
    await settle();
    expect(dialog().textContent).toContain('El chat dejará de estar disponible y se libera la memoria del modelo.');
  });

  it('while stopping it says so and blocks the buttons, then asks the card to check again', async () => {
    button('Parar').click();
    await settle();
    button('Parar RabbitMQ').click();
    await settle();

    expect(actions.map(({ service, action }) => [service, action])).toEqual([['RABBITMQ', 'STOP']]);
    expect(progress()).toBe('Parando…');
    expect(button('Levantar').disabled && button('Parar').disabled).toBe(true);

    status.set(running('ASSISTANT'));
    actions[0].answer.next({ service: 'RABBITMQ', state: 'STOPPED' });
    await settle();
    expect(progress()).toBe('');
    expect(fixture.componentInstance.rechecks).toBe(1);
    expect(button('Levantar').disabled).toBe(false);
  });

  it('after starting it keeps checking until the service answers', async () => {
    vi.useFakeTimers();
    status.set(running('ASSISTANT'));
    fixture.componentInstance.up.set(false);
    await settle();

    button('Levantar').click();
    await settle();
    expect(actions.map(({ action }) => action)).toEqual(['START']);
    expect(progress()).toBe('Levantando…');

    actions[0].answer.next({ service: 'RABBITMQ', state: 'RUNNING' });
    await settle();
    vi.advanceTimersByTime(RECHECK_MS * 2);
    expect(fixture.componentInstance.rechecks).toBe(3);
    expect(progress()).toBe('Levantando…');

    fixture.componentInstance.up.set(true);
    await settle();
    expect(progress()).toBe('');
    vi.advanceTimersByTime(RECHECK_MS * 3);
    expect(fixture.componentInstance.rechecks).toBe(3);
  });

  it('says so when the service takes too long to answer', async () => {
    vi.useFakeTimers();
    status.set(running('ASSISTANT'));
    fixture.componentInstance.up.set(false);
    await settle();
    button('Levantar').click();
    actions[0].answer.next({ service: 'RABBITMQ', state: 'RUNNING' });
    vi.advanceTimersByTime(START_PATIENCE_MS);
    await settle();

    expect(progress()).toBe('');
    expect(root.querySelector('.warning')?.textContent).toContain('Tarda más de lo normal');
  });

  it('explains errors in Spanish and unblocks the buttons', async () => {
    button('Parar').click();
    await settle();
    button('Parar RabbitMQ').click();
    await settle();
    actions[0].answer.error(new ApiError('control-unavailable', 'No se ha podido hablar con Docker para parar o levantar el servicio.'));
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('No se ha podido hablar con Docker');
    expect(button('Parar').disabled).toBe(false);
  });

  it('without the Compose there are no buttons and it explains why', async () => {
    status.set({ available: false, reason: 'Solo se puede parar y levantar desde aquí con su Docker Compose.', services: [] });
    await settle();

    expect(root.querySelectorAll('button').length).toBe(0);
    expect(root.querySelector('.unavailable')?.textContent).toContain('con su Docker Compose');
  });

  it('a service that does not exist shows how to create it instead of the buttons', async () => {
    status.set({
      available: true,
      services: [
        { service: 'RABBITMQ', state: 'RUNNING' },
        { service: 'ASSISTANT', state: 'MISSING', message: 'Se ha levantado con OLLAMA_REPLICAS=0.' },
      ],
    });
    fixture.componentInstance.service.set('ASSISTANT');
    await settle();

    expect(button('Levantar')).toBeUndefined();
    expect(root.querySelector('.unavailable')?.textContent).toContain('OLLAMA_REPLICAS=0');
  });

  it('shows nothing while the control status is unknown', async () => {
    status.set(null);
    await settle();
    expect(button('Levantar')).toBeUndefined();
    expect(root.querySelector('.unavailable')).toBeNull();
  });
});
