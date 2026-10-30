import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, firstValueFrom, of } from 'rxjs';
import { routes } from '../../../app.routes';
import { StatusApiService } from '../../../core/status/status-api.service';
import { FakeEventSource } from '../../../testing/fake-event-source';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { BindingsApi } from '../bindings/bindings-api';
import { ConsumerSessionService } from '../consumers/consumer-session.service';
import { ConsumersApi } from '../consumers/consumers-api';
import { EVENT_SOURCE_FACTORY } from '../consumers/event-source-factory';
import { ExchangesApi } from '../exchanges/exchanges-api';
import { PublishApi } from '../publish/publish-api';
import { QueuesApi } from '../queues/queues-api';

describe('leaving the RabbitMQ lab with consumers (CA-9.14)', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let http: HttpTestingController;
  let sources: FakeEventSource[];
  let session: ConsumerSessionService;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    sources = [];
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: StatusApiService, useValue: { getStatus: () => NEVER } },
        { provide: ExchangesApi, useValue: { list: () => NEVER } },
        { provide: QueuesApi, useValue: { list: () => NEVER } },
        { provide: BindingsApi, useValue: { list: () => NEVER } },
        { provide: PublishApi, useValue: {} },
        {
          provide: ConsumersApi,
          useValue: {
            start: (_s: string, name: string, queue: string, ackMode: string) => of({ id: `id-${name}`, name, queue, ackMode }),
          },
        },
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            const source = new FakeEventSource(url);
            sources.push(source);
            return source;
          },
        },
      ],
    });
    router = TestBed.inject(Router);
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/rabbitmq/queues');
    http.expectOne('/api/rabbitmq/status').flush({ status: 'CONNECTED', version: '4.4.0' });
    harness.detectChanges();
    await harness.fixture.whenStable();
    session = harness.routeDebugElement!.injector.get(ConsumerSessionService);
  });

  afterEach(() => http.verify());

  async function startConsumer(name: string, queue = 'pedidos') {
    const pending = firstValueFrom(session.start(name, queue, 'MANUAL'));
    await Promise.resolve();
    if (sources.length === 1 && !sources[0].closed && sessionNotAnnounced) {
      sources[0].emit('session-started', { sessionId: 's1' });
      sessionNotAnnounced = false;
    }
    await pending;
    harness.detectChanges();
  }
  let sessionNotAnnounced = true;
  beforeEach(() => (sessionNotAnnounced = true));

  /** El diálogo del propio laboratorio, no el de la pantalla de colas que también está en la página. */
  const dialog = () =>
    (harness.routeNativeElement as HTMLElement).querySelector('section.lab > app-confirm-dialog dialog') as HTMLDialogElement;
  const dialogButton = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;
  /** Deja correr lo pendiente sin esperar a que termine la navegación, que está parada en el diálogo. */
  const settle = async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
  };

  it('does not ask when there are no active consumers', async () => {
    await harness.navigateByUrl('/status');

    expect(router.url).toBe('/status');
    expect(dialog()).toBeNull();
  });

  it('does not ask when changing area inside the lab, even with consumers', async () => {
    await startConsumer('c1');

    await harness.navigateByUrl('/rabbitmq/exchanges');
    await harness.navigateByUrl('/rabbitmq/consumers');
    await harness.navigateByUrl('/rabbitmq/publish');

    expect(router.url).toBe('/rabbitmq/publish');
    expect(dialog().hasAttribute('open')).toBe(false);
    expect(session.activeConsumers()).toHaveLength(1);
    expect(sources[0].closed).toBe(false);
  });

  it('asks for confirmation naming the consumers that will be stopped', async () => {
    await startConsumer('c1');
    await startConsumer('c2', 'fallidos');

    const navigation = harness.navigateByUrl('/status');
    await settle();

    expect(dialog().hasAttribute('open')).toBe(true);
    expect(dialog().textContent).toContain('Salir del Laboratorio RabbitMQ');
    expect(dialog().textContent).toContain('2 consumidores activos');
    expect(dialog().textContent).toContain('volverán a la cola');
    expect(Array.from(dialog().querySelectorAll('li')).map((li) => li.textContent?.trim())).toEqual([
      'c1 (cola «pedidos»)',
      'c2 (cola «fallidos»)',
    ]);
    dialogButton('Quedarme').click();
    await navigation;
  });

  it('stays in the lab, with the consumers running, when cancelling', async () => {
    await startConsumer('c1');
    const before = router.url;

    const navigation = harness.navigateByUrl('/status');
    await settle();
    dialogButton('Quedarme').click();
    await navigation;

    expect(router.url).toBe(before);
    expect(session.activeConsumers()).toHaveLength(1);
    expect(sources[0].closed).toBe(false);
  });

  it('stops the consumers, closing the stream, and leaves when confirming', async () => {
    await startConsumer('c1');

    const navigation = harness.navigateByUrl('/status');
    await settle();
    dialogButton('Salir y detenerlos').click();
    await navigation;

    expect(router.url).toBe('/status');
    expect(sources[0].closed).toBe(true);
  });

  it('does not ask again after the consumers were stopped by hand', async () => {
    await startConsumer('c1');
    session.stopAll();

    await harness.navigateByUrl('/status');

    expect(router.url).toBe('/status');
  });
});
