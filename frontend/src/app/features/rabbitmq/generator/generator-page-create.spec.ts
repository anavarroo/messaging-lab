import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { TemplatePreset } from '../../../shared/generator/emitter-view';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { ExchangesApi } from '../exchanges/exchanges-api';
import { Queue, QueuesApi } from '../queues/queues-api';
import {
  Emitter,
  EmitterRequest,
  GeneratorApi,
  SimulatedConsumer,
  SimulatedConsumerRequest,
} from './generator-api';
import { GeneratorPage, REFRESH_MS } from './generator-page';

describe('GeneratorPage: new emitters, simulated consumers and full queues', () => {
  const preset: TemplatePreset = {
    id: 'temperatura',
    name: 'Sensor de temperatura',
    description: 'Una temperatura.',
    template: '{"t": {{deriva(20, 0.5)}}}',
    suggestedInterval: { mode: 'FIXED', fixedMs: 2000 },
    suggestedRoutingKey: 'lab.temperatura',
    onlyWhenChanged: true,
  };
  const queue = (name: string, ready: number): Queue => ({ name, ready, unacknowledged: 0, total: ready });

  let emitters: Emitter[];
  let consumers: SimulatedConsumer[];
  let queues: Queue[];
  let createEmitter: ReturnType<typeof vi.fn<(request: EmitterRequest) => Observable<Emitter>>>;
  let createConsumer: ReturnType<typeof vi.fn<(request: SimulatedConsumerRequest) => Observable<SimulatedConsumer>>>;
  let deleteConsumer: ReturnType<typeof vi.fn<(id: string) => Observable<void>>>;
  let actOnConsumer: ReturnType<typeof vi.fn<(id: string, action: 'START' | 'STOP') => Observable<SimulatedConsumer>>>;
  let fixture: ComponentFixture<GeneratorPage>;
  let page: HTMLElement;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    vi.useFakeTimers();
    emitters = [];
    consumers = [];
    queues = [queue('cocina', 0)];
    createEmitter = vi.fn((request: EmitterRequest) => {
      const created: Emitter = { ...request, id: 'e1', status: 'STOPPED', published: 0, unrouted: 0, ratePerSecond: 0 };
      emitters = [...emitters, created];
      return of(created);
    });
    createConsumer = vi.fn((request: SimulatedConsumerRequest) => {
      const created: SimulatedConsumer = { ...request, id: 'c1', status: 'STOPPED', processed: 0, failed: 0, ratePerSecond: 0 };
      consumers = [...consumers, created];
      return of(created);
    });
    deleteConsumer = vi.fn((id: string) => {
      consumers = consumers.filter((c) => c.id !== id);
      return of(undefined);
    });
    actOnConsumer = vi.fn((id: string, action: 'START' | 'STOP') => {
      const updated = { ...consumers.find((c) => c.id === id)!, status: action === 'START' ? 'RUNNING' : 'STOPPED' } as SimulatedConsumer;
      consumers = consumers.map((c) => (c.id === id ? updated : c));
      return of(updated);
    });
    await TestBed.configureTestingModule({
      imports: [GeneratorPage],
      providers: [
        {
          provide: GeneratorApi,
          useValue: {
            listEmitters: () => of(emitters),
            getSettings: () =>
              of({ maxRatePerSecond: 100, absoluteMaxRatePerSecond: 500, ratePerSecondInUse: 0, maxEmitters: 20, maxSimulatedConsumers: 20 }),
            listConsumers: () => of(consumers),
            listTemplates: () => of([preset]),
            previewTemplate: (template: string) =>
              template.includes('foo')
                ? throwError(() => new ApiError('validation', 'Error en la plantilla (línea 1, columna 3): la función «foo» no existe.'))
                : of({ body: '{"t": 20.2}', bytes: 11 }),
            createEmitter,
            createConsumer,
            deleteConsumer,
            actOnConsumer,
          },
        },
        { provide: QueuesApi, useValue: { list: () => of(queues) } },
        { provide: ExchangesApi, useValue: { list: () => of([{ name: 'pizzeria', type: 'TOPIC', predefined: false }]) } },
        { provide: RabbitMqConnection, useValue: { reportFailure: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(GeneratorPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => vi.useRealTimers());

  async function settle(ms = 1) {
    await vi.advanceTimersByTimeAsync(ms);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(1);
    fixture.detectChanges();
  }
  const set = (selector: string, value: string) => {
    const field = page.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(selector)!;
    field.value = value;
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  };
  const click = (scope: ParentNode, label: string) => {
    (Array.from(scope.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement).click();
    fixture.detectChanges();
  };
  const submitForm = (index: number) => {
    page.querySelectorAll('form')[index].dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const emitterForm = () => page.querySelector('app-emitter-form form') as HTMLFormElement;
  const text = (selector: string) => page.querySelector(selector)?.textContent?.trim();

  describe('layout', () => {
    it('separates the emitters from the consumers in two panels of their own', () => {
      const emitters = page.querySelector('.emitters-panel')!;
      const consumers = page.querySelector('.consumers-panel')!;

      expect(emitters.querySelector('h4')?.textContent).toBe('Emisores');
      expect(emitters.querySelector('app-emitter-form')).not.toBeNull();
      expect(emitters.querySelector('app-emitter-list, .empty')).not.toBeNull();
      expect(emitters.querySelector('app-simulated-consumers')).toBeNull();
      expect(consumers.querySelector('app-simulated-consumers')).not.toBeNull();
      expect(consumers.querySelector('app-emitter-form')).toBeNull();
      expect(consumers.textContent).toContain('Consumen');
    });
  });

  describe('new emitter', () => {
    it('is filled by a template of the catalog, including the suggested routing key', () => {
      set('#emitter-preset', 'temperatura');
      click(page, 'Usar plantilla');

      expect(page.querySelector<HTMLTextAreaElement>('#emitter-template')!.value).toBe(preset.template);
      expect(page.querySelector<HTMLInputElement>('#emitter-fixed')!.value).toBe('2000');
      expect(page.querySelector<HTMLInputElement>('#destination-routing-key')!.value).toBe('lab.temperatura');
    });

    it('creates the emitter with its destination, announces it and clears the form', async () => {
      set('#emitter-preset', 'temperatura');
      click(page, 'Usar plantilla');
      set('#destination-exchange', 'pizzeria');

      submitForm(1);
      await settle();

      expect(createEmitter).toHaveBeenCalledWith({
        name: 'Sensor de temperatura',
        destination: { type: 'EXCHANGE', exchange: 'pizzeria', routingKey: 'lab.temperatura' },
        interval: { mode: 'FIXED', fixedMs: 2000 },
        template: preset.template,
        maxMessages: null,
        maxDurationSeconds: null,
        onlyWhenChanged: true,
      });
      expect(text('.notice')).toContain('Emisor «Sensor de temperatura» creado');
      expect(page.querySelector<HTMLTextAreaElement>('#emitter-template')!.value).toBe('');
      expect(page.querySelectorAll('li.emitter')).toHaveLength(1);
    });

    it('does not call the server without a destination and shows what is missing', () => {
      set('#emitter-name', 'x');
      set('#emitter-template', 'y');

      submitForm(1);

      expect(createEmitter).not.toHaveBeenCalled();
      expect(text('#destination-error')).toBe('Elige el exchange al que publicar.');
    });

    it('rejects an invalid template when the server refuses it, keeping what was typed', async () => {
      createEmitter.mockReturnValue(
        throwError(() => new ApiError('validation', 'Error en la plantilla (línea 1, columna 3): la función «foo» no existe.')),
      );
      set('#emitter-name', 'malo');
      set('#emitter-template', '{{foo()}}');
      set('#destination-exchange', 'pizzeria');

      submitForm(1);
      await settle();

      expect(text('.submit-error')).toContain('Error en la plantilla (línea 1, columna 3)');
      expect(page.querySelector<HTMLTextAreaElement>('#emitter-template')!.value).toBe('{{foo()}}');
      expect(page.querySelectorAll('li.emitter')).toHaveLength(0);
    });

    it('previews through the server and rejects an invalid template with its position', () => {
      set('#emitter-template', '{"t": 1}');
      click(emitterForm(), 'Vista previa');
      expect(text('.preview-body')).toBe('{"t": 20.2}');

      set('#emitter-template', '{{foo()}}');
      click(emitterForm(), 'Vista previa');
      expect(text('.preview-error')).toContain('Error en la plantilla (línea 1, columna 3)');
    });
  });

  describe('simulated consumers', () => {
    const fillConsumer = () => {
      set('#consumer-name', 'cocinero');
      set('#consumer-queue', 'cocina');
    };

    it('creates a consumer, announces it and shows it in the list', async () => {
      fillConsumer();

      submitForm(2);
      await settle();

      expect(createConsumer).toHaveBeenCalledWith({
        name: 'cocinero',
        queue: 'cocina',
        processing: { mode: 'FIXED', fixedMs: 100 },
        failurePercent: 0,
        onFailure: 'REQUEUE',
      });
      expect(text('.notice')).toContain('Consumidor «cocinero» creado');
      expect(page.querySelectorAll('li.consumer')).toHaveLength(1);
      expect(page.querySelector<HTMLInputElement>('#consumer-name')!.value).toBe('');
    });

    it('shows the error when the server refuses the consumer', async () => {
      createConsumer.mockReturnValue(throwError(() => new ApiError('conflict', 'Ya existe un consumidor con ese nombre.')));
      fillConsumer();

      submitForm(2);
      await settle();

      expect(text('app-simulated-consumers .error')).toContain('Ya existe un consumidor con ese nombre.');
    });

    it('starts and stops a consumer', async () => {
      fillConsumer();
      submitForm(2);
      await settle();
      const item = () => page.querySelector('li.consumer')!;

      click(item(), 'Iniciar');
      await settle();
      expect(actOnConsumer).toHaveBeenCalledWith('c1', 'START');
      expect(item().querySelector('.badge')?.textContent?.trim()).toBe('En marcha');

      click(item(), 'Parar');
      await settle();
      expect(actOnConsumer).toHaveBeenLastCalledWith('c1', 'STOP');
    });

    it('removes a consumer after confirming, and keeps it if cancelled', async () => {
      fillConsumer();
      submitForm(2);
      await settle();

      click(page.querySelector('li.consumer')!, 'Eliminar');
      await settle();
      const dialog = page.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.textContent).toContain('Eliminar el consumidor «cocinero»');
      click(dialog, 'Cancelar');
      await settle();
      expect(deleteConsumer).not.toHaveBeenCalled();
      expect(page.querySelectorAll('li.consumer')).toHaveLength(1);

      click(page.querySelector('li.consumer')!, 'Eliminar');
      await settle();
      click(page.querySelector('dialog')!, 'Eliminar');
      await settle();
      expect(deleteConsumer).toHaveBeenCalledWith('c1');
      expect(page.querySelectorAll('li.consumer')).toHaveLength(0);
      expect(text('.notice')).toContain('Consumidor «cocinero» eliminado.');
    });
  });

  describe('full queue warning', () => {
    it('does not warn up to 10 000 ready messages', async () => {
      queues = [queue('cocina', 10_000)];
      await settle(REFRESH_MS);

      expect(page.querySelector('.queue-warning')).toBeNull();
    });

    it('warns about each queue over 10 000 ready messages and stops when it drains', async () => {
      queues = [queue('cocina', 10_001), queue('barra', 20_000), queue('vacia', 3)];
      await settle(REFRESH_MS);

      const warning = page.querySelector('.queue-warning')!;
      expect(warning.getAttribute('role')).toBe('status');
      expect(warning.querySelectorAll('li')).toHaveLength(2);
      expect(warning.textContent).toContain('La cola «cocina» tiene 10001 mensajes listos');
      expect(warning.textContent).toContain('La cola «barra» tiene 20000 mensajes listos');
      expect(warning.textContent).not.toContain('vacia');

      queues = [queue('cocina', 50)];
      await settle(REFRESH_MS);
      expect(page.querySelector('.queue-warning')).toBeNull();
    });
  });
});
