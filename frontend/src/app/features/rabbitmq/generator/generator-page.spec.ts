import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { ExchangesApi } from '../exchanges/exchanges-api';
import { QueuesApi } from '../queues/queues-api';
import { Emitter, GeneratorApi, GeneratorSettings } from './generator-api';
import { GeneratorPage, REFRESH_MS } from './generator-page';
import { EmitterAction } from '../../../shared/generator/emitter-view';

describe('GeneratorPage', () => {
  const emitter = (id: string, extra: Partial<Emitter> = {}): Emitter => ({
    id,
    name: `emisor-${id}`,
    destination: { type: 'EXCHANGE', exchange: 'pizzeria', routingKey: 'pedido.pizza' },
    interval: { mode: 'FIXED', fixedMs: 100 },
    template: 'x',
    onlyWhenChanged: false,
    status: 'STOPPED',
    published: 0,
    unrouted: 0,
    ratePerSecond: 0,
    ...extra,
  });
  const settings = (extra: Partial<GeneratorSettings> = {}): GeneratorSettings => ({
    maxRatePerSecond: 100,
    absoluteMaxRatePerSecond: 500,
    ratePerSecondInUse: 0,
    maxEmitters: 20,
    maxSimulatedConsumers: 20,
    ...extra,
  });

  let backend: Emitter[];
  let fixture: ComponentFixture<GeneratorPage>;
  let page: HTMLElement;
  let listEmitters: ReturnType<typeof vi.fn<() => Observable<Emitter[]>>>;
  let getSettings: ReturnType<typeof vi.fn<() => Observable<GeneratorSettings>>>;
  let act: ReturnType<typeof vi.fn<(id: string, action: EmitterAction) => Observable<Emitter>>>;
  let deleteEmitter: ReturnType<typeof vi.fn<(id: string) => Observable<void>>>;
  let stopAll: ReturnType<typeof vi.fn<() => Observable<void>>>;
  let updateSettings: ReturnType<typeof vi.fn<(limit: number) => Observable<GeneratorSettings>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    vi.useFakeTimers();
    // el «backend»: lo que devuelve el listado refleja las acciones, como el real
    backend = [emitter('1', { status: 'RUNNING', published: 5 }), emitter('2')];
    listEmitters = vi.fn(() => of(backend.map((e) => ({ ...e }))));
    getSettings = vi.fn(() => of(settings({ ratePerSecondInUse: 10 })));
    act = vi.fn((id: string, action: EmitterAction) => {
      const updated = { ...backend.find((e) => e.id === id)!, status: action === 'STOP' ? 'STOPPED' : action === 'PAUSE' ? 'PAUSED' : 'RUNNING' } as Emitter;
      backend = backend.map((e) => (e.id === id ? updated : e));
      return of(updated);
    });
    deleteEmitter = vi.fn(() => of(undefined));
    stopAll = vi.fn(() => of(undefined));
    updateSettings = vi.fn((limit: number) => of(settings({ maxRatePerSecond: limit })));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [GeneratorPage],
      providers: [
        {
          provide: GeneratorApi,
          useValue: {
            listEmitters,
            getSettings,
            actOnEmitter: act,
            deleteEmitter,
            stopAll,
            updateSettings,
            listConsumers: () => of([]),
            listTemplates: () => of([]),
            previewTemplate: () => of({ body: '{}', bytes: 2 }),
          },
        },
        { provide: QueuesApi, useValue: { list: () => of([]) } },
        { provide: ExchangesApi, useValue: { list: () => of([]) } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(GeneratorPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => vi.useRealTimers());

  /** Deja pasar el tiempo simulado. El mínimo es 1 ms: los temporizadores falsos no disparan un intervalo de 0. */
  async function settle(ms = 1) {
    await vi.advanceTimersByTimeAsync(ms);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(1);
    fixture.detectChanges();
  }

  const items = () => Array.from(page.querySelectorAll<HTMLElement>('li.emitter'));
  const itemOf = (name: string) => items().find((item) => item.querySelector('h5')?.textContent === name)!;
  const buttonIn = (root: Element, label: string) =>
    Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
  /** Valor de un contador de la tarjeta de un emisor, por el nombre de su etiqueta. */
  const counter = (item: HTMLElement, label: string) => {
    const terms = Array.from(item.querySelectorAll('.counters dt'));
    return terms.find((dt) => dt.textContent?.trim() === label)?.nextElementSibling?.textContent?.trim();
  };
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const notice = () => page.querySelector('.notice')?.textContent?.trim();
  const dialog = () => page.querySelector('dialog') as HTMLDialogElement;
  const limitInput = () => page.querySelector<HTMLInputElement>('#generator-limit')!;

  describe('listing', () => {
    it('shows the emitters with their real state and counters (CA-5.1)', () => {
      expect(items()).toHaveLength(2);
      expect(itemOf('emisor-1').querySelector('.badge')?.textContent?.trim()).toBe('En marcha');
      expect(counter(itemOf('emisor-1'), 'Publicados')).toBe('5');
      expect(itemOf('emisor-2').querySelector('.badge')?.textContent?.trim()).toBe('Parado');
    });

    it('describes the destination: an exchange with its routing key, or a queue', async () => {
      listEmitters.mockReturnValue(
        of([emitter('1'), emitter('2', { destination: { type: 'QUEUE', queue: 'cocina' } }), emitter('3', { destination: { type: 'EXCHANGE', exchange: 'avisos', routingKey: '' } })]),
      );
      await settle(REFRESH_MS);

      expect(itemOf('emisor-1').querySelector('.where')?.textContent).toContain('Exchange «pizzeria» · routing key «pedido.pizza»');
      expect(itemOf('emisor-2').querySelector('.where')?.textContent).toContain('Cola «cocina»');
      expect(itemOf('emisor-3').querySelector('.where')?.textContent).toContain('Exchange «avisos»');
      expect(itemOf('emisor-3').querySelector('.where')?.textContent).not.toContain('routing key');
    });

    it('says so when there are no emitters', async () => {
      listEmitters.mockReturnValue(of([]));
      await settle(REFRESH_MS);

      expect(page.textContent).toContain('Todavía no hay emisores.');
    });

    it('shows a loading indicator until the first answer', async () => {
      const pending = new Subject<Emitter[]>();
      listEmitters.mockReturnValue(pending);
      fixture.destroy();
      fixture = TestBed.createComponent(GeneratorPage);
      page = fixture.nativeElement as HTMLElement;
      await settle();

      expect(page.textContent).toContain('Cargando los emisores');
      pending.next([]);
      pending.complete();
      await settle();
      expect(page.textContent).not.toContain('Cargando los emisores');
    });
  });

  describe('refreshing by itself (CA-5.2)', () => {
    it('asks for the state right away when opened', () => {
      expect(listEmitters).toHaveBeenCalledTimes(1);
      expect(getSettings).toHaveBeenCalledTimes(1);
    });

    it('asks again every couple of seconds and shows the new counters', async () => {
      listEmitters.mockReturnValue(of([emitter('1', { status: 'RUNNING', published: 77 })]));

      await settle(REFRESH_MS);

      expect(listEmitters).toHaveBeenCalledTimes(2);
      expect(counter(itemOf('emisor-1'), 'Publicados')).toBe('77');
      await settle(REFRESH_MS * 3);
      expect(listEmitters).toHaveBeenCalledTimes(5);
    });

    it('does not pile up requests when the backend is slow', async () => {
      const slow = new Subject<Emitter[]>();
      listEmitters.mockReturnValue(slow);

      await settle(REFRESH_MS * 5);

      // cada consulta nueva sustituye a la anterior: las pendientes se cancelan
      expect(slow.observed).toBe(true);
      expect(slow.observers.length).toBe(1);
    });

    it('stops asking when the page is destroyed', async () => {
      fixture.destroy();
      listEmitters.mockClear();

      await vi.advanceTimersByTimeAsync(REFRESH_MS * 5);

      expect(listEmitters).not.toHaveBeenCalled();
    });

    it('keeps the last state and shows the error when a refresh fails, then recovers (CA-11.6)', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      listEmitters.mockReturnValue(throwError(() => failure));

      await settle(REFRESH_MS);

      expect(alerts()).toContain('RabbitMQ no está disponible.');
      expect(items()).toHaveLength(2);
      expect(reportFailure).toHaveBeenCalledWith(failure);

      listEmitters.mockReturnValue(of([emitter('1')]));
      await settle(REFRESH_MS);
      expect(alerts()).not.toContain('RabbitMQ no está disponible.');
      expect(items()).toHaveLength(1);
    });
  });

  describe('controls', () => {
    it('starts an emitter and shows it running with the controls of that state', async () => {
      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();

      expect(act).toHaveBeenCalledWith('2', 'START');
      expect(itemOf('emisor-2').querySelector('.badge')?.textContent?.trim()).toBe('En marcha');
    });

    it.each([
      ['Pausar', 'PAUSE'],
      ['Parar', 'STOP'],
    ])('%s sends the action %s', async (label, action) => {
      buttonIn(itemOf('emisor-1'), label).click();
      await settle();

      expect(act).toHaveBeenCalledWith('1', action);
    });

    it('resumes a paused emitter', async () => {
      listEmitters.mockReturnValue(of([emitter('1', { status: 'PAUSED' })]));
      await settle(REFRESH_MS);

      buttonIn(itemOf('emisor-1'), 'Reanudar').click();
      await settle();

      expect(act).toHaveBeenCalledWith('1', 'RESUME');
    });

    it('asks for the real state again right after an action', async () => {
      listEmitters.mockClear();

      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();

      expect(listEmitters).toHaveBeenCalledTimes(1);
    });

    it('blocks the buttons of that emitter while the action is in progress and ignores repeats (CA-11.5)', async () => {
      const response = new Subject<Emitter>();
      act.mockReturnValue(response);

      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();
      expect(buttonIn(itemOf('emisor-2'), 'Iniciar').disabled).toBe(true);
      expect(buttonIn(itemOf('emisor-1'), 'Pausar').disabled).toBe(false);
      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();
      expect(act).toHaveBeenCalledTimes(1);

      backend = backend.map((e) => (e.id === '2' ? { ...e, status: 'RUNNING' } : e));
      response.next(emitter('2', { status: 'RUNNING' }));
      response.complete();
      await settle();
      expect(itemOf('emisor-2').querySelector('.badge')?.textContent?.trim()).toBe('En marcha');
    });

    it('shows the reason and keeps the list when an action is refused, for example by the limit', async () => {
      act.mockReturnValue(
        throwError(() => new ApiError('conflict', 'No se puede poner en marcha «emisor-2»: añadiría 10 mensajes por segundo…')),
      );

      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();

      expect(alerts()).toContain('No se puede poner en marcha «emisor-2»: añadiría 10 mensajes por segundo…');
      expect(items()).toHaveLength(2);
      expect(buttonIn(itemOf('emisor-2'), 'Iniciar').disabled).toBe(false);
    });

    it('reports a failure to the connection so it can offer a retry (CA-11.1)', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      act.mockReturnValue(throwError(() => failure));

      buttonIn(itemOf('emisor-2'), 'Iniciar').click();
      await settle();

      expect(reportFailure).toHaveBeenCalledWith(failure);
    });
  });

  describe('deleting', () => {
    it('asks for confirmation before deleting, saying that a running emitter is stopped first', async () => {
      buttonIn(itemOf('emisor-1'), 'Eliminar').click();
      await settle();

      expect(dialog().hasAttribute('open')).toBe(true);
      expect(dialog().textContent).toContain('Eliminar el emisor «emisor-1»');
      expect(dialog().textContent).toContain('se parará y se eliminará');
      expect(deleteEmitter).not.toHaveBeenCalled();
    });

    it('does not mention stopping for an emitter that is already stopped', async () => {
      buttonIn(itemOf('emisor-2'), 'Eliminar').click();
      await settle();

      expect(dialog().textContent).not.toContain('se parará');
      expect(dialog().textContent).toContain('se quedan en sus colas');
    });

    it('deletes after confirming and removes it from the list', async () => {
      buttonIn(itemOf('emisor-2'), 'Eliminar').click();
      await settle();

      buttonIn(dialog(), 'Eliminar').click();
      await settle();

      expect(deleteEmitter).toHaveBeenCalledWith('2');
      expect(notice()).toBe('Emisor «emisor-2» eliminado.');
    });

    it('does not delete when cancelling', async () => {
      buttonIn(itemOf('emisor-2'), 'Eliminar').click();
      await settle();

      buttonIn(dialog(), 'Cancelar').click();
      await settle();

      expect(deleteEmitter).not.toHaveBeenCalled();
      expect(items()).toHaveLength(2);
    });

    it('shows the error when the deletion fails', async () => {
      deleteEmitter.mockReturnValue(throwError(() => new ApiError('not-found', 'El emisor no existe: puede que ya se haya eliminado.')));
      buttonIn(itemOf('emisor-2'), 'Eliminar').click();
      await settle();

      buttonIn(dialog(), 'Eliminar').click();
      await settle();

      expect(alerts()).toContain('El emisor no existe: puede que ya se haya eliminado.');
    });
  });

  describe('Stop all (CA-1.5)', () => {
    const stopAllButton = () => page.querySelector<HTMLButtonElement>('button.stop-all')!;

    it('is always visible, even with no emitters', async () => {
      listEmitters.mockReturnValue(of([]));
      await settle(REFRESH_MS);

      expect(stopAllButton().textContent?.trim()).toBe('Parar todo');
    });

    it('stops everything, announces it and asks for the real state', async () => {
      listEmitters.mockClear();

      stopAllButton().click();
      await settle();

      expect(stopAll).toHaveBeenCalledTimes(1);
      expect(notice()).toBe('Se ha parado todo lo que estaba en marcha.');
      expect(listEmitters).toHaveBeenCalledTimes(1);
    });

    it('blocks the button while it works and ignores repeats', async () => {
      const response = new Subject<void>();
      stopAll.mockReturnValue(response);

      stopAllButton().click();
      await settle();
      expect(stopAllButton().disabled).toBe(true);
      expect(stopAllButton().textContent?.trim()).toBe('Parando…');
      stopAllButton().click();
      await settle();
      expect(stopAll).toHaveBeenCalledTimes(1);

      response.next();
      response.complete();
      await settle();
      expect(stopAllButton().disabled).toBe(false);
    });

    it('shows the error if it fails', async () => {
      stopAll.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));

      stopAllButton().click();
      await settle();

      expect(alerts()).toContain('RabbitMQ no está disponible.');
    });
  });

  describe('global limit (CA-3.1, CA-3.2)', () => {
    async function type(value: string) {
      limitInput().value = value;
      limitInput().dispatchEvent(new Event('input'));
      await settle();
    }

    const submit = async () => {
      buttonIn(page.querySelector('.limit-form')!, 'Cambiar límite').click();
      await settle();
    };

    it('shows the limit, what is in use and the absolute maximum', () => {
      const text = page.querySelector('.limit-summary')?.textContent ?? '';

      expect(text).toContain('no pueden sumar más de 100 mensajes por segundo');
      expect(text).toContain('Ahora usan 10');
      expect(text).toContain('El máximo absoluto es 500');
      expect(limitInput().value).toBe('100');
    });

    it('changes the limit and shows the new one', async () => {
      await type('300');
      await submit();

      expect(updateSettings).toHaveBeenCalledWith(300);
      expect(notice()).toBe('Límite cambiado a 300 mensajes por segundo.');
      expect(page.querySelector('.limit-summary')?.textContent).toContain('300');
    });

    it.each([['0'], ['-5'], ['501'], ['2.5'], ['']])('rejects %j before calling the backend', async (value) => {
      await type(value);
      await submit();

      expect(page.querySelector('#generator-limit-error')?.textContent).toContain('número entero entre 1 y 500');
      expect(limitInput().getAttribute('aria-invalid')).toBe('true');
      expect(updateSettings).not.toHaveBeenCalled();
    });

    it('accepts the extremes 1 and 500', async () => {
      await type('1');
      await submit();
      await type('500');
      await submit();

      expect(updateSettings).toHaveBeenNthCalledWith(1, 1);
      expect(updateSettings).toHaveBeenNthCalledWith(2, 500);
    });

    it('explains why the backend refuses to lower it below what is running', async () => {
      updateSettings.mockReturnValue(
        throwError(() => new ApiError('conflict', 'No se puede bajar el límite a 5 mensajes por segundo: ya hay 10 en marcha. Pausa o para algún emisor antes.')),
      );
      await type('5');
      await submit();

      expect(page.querySelector('#generator-limit-error')?.textContent).toContain('No se puede bajar el límite a 5');
      expect(page.querySelector('.limit-summary')?.textContent).toContain('100');
    });

    it('blocks the button while saving', async () => {
      const response = new Subject<GeneratorSettings>();
      updateSettings.mockReturnValue(response);
      await type('200');

      await submit();
      expect(buttonIn(page.querySelector('.limit-form')!, 'Cambiar límite').disabled).toBe(true);
      response.next(settings({ maxRatePerSecond: 200 }));
      await settle();
      expect(buttonIn(page.querySelector('.limit-form')!, 'Cambiar límite').disabled).toBe(false);
    });

    it('does not overwrite what the user is typing when the page refreshes', async () => {
      await type('250');

      await settle(REFRESH_MS * 2);

      expect(limitInput().value).toBe('250');
    });

    it('follows the limit of the server while the user is not editing it', async () => {
      getSettings.mockReturnValue(of(settings({ maxRatePerSecond: 150 })));

      await settle(REFRESH_MS);

      expect(limitInput().value).toBe('150');
    });
  });

  describe('accessibility', () => {
    it('announces notices politely and errors as alerts', async () => {
      stopAll.mockReturnValue(of(undefined));
      page.querySelector<HTMLButtonElement>('button.stop-all')!.click();
      await settle();

      expect(page.querySelector('.feedback')?.getAttribute('aria-live')).toBe('polite');
      expect(page.querySelector('section')?.getAttribute('aria-labelledby')).toBe('generator-title');
    });
  });
});
