import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NEVER } from 'rxjs';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { BindingsApi } from '../bindings/bindings-api';
import { ExchangesApi } from '../exchanges/exchanges-api';
import { PublishApi } from '../publish/publish-api';
import { QueuesApi } from '../queues/queues-api';
import { RabbitMqLab } from './rabbitmq-lab';

describe('RabbitMqLab', () => {
  const STATUS = '/api/rabbitmq/status';
  const AREAS = ['Exchanges', 'Colas', 'Bindings', 'Publicación', 'Consumidores'];

  let harness: RouterTestingHarness;
  let router: Router;
  let http: HttpTestingController;
  let page: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        // las pantallas de cada área se prueban aparte: aquí solo importa que se muestren
        { provide: ExchangesApi, useValue: { list: () => NEVER } },
        { provide: QueuesApi, useValue: { list: () => NEVER } },
        { provide: BindingsApi, useValue: { list: () => NEVER } },
        { provide: PublishApi, useValue: {} },
      ],
    });
    router = TestBed.inject(Router);
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => http.verify());

  /** Entra en el laboratorio; la consulta de estado queda pendiente hasta que la prueba la responda. */
  async function enter(url = '/rabbitmq') {
    await harness.navigateByUrl(url);
    page = harness.routeNativeElement as HTMLElement;
  }

  async function respond(answer: () => void) {
    answer();
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  const connect = () => respond(() => http.expectOne(STATUS).flush({ status: 'CONNECTED', version: '4.4.0' }));
  const failWith503 = () =>
    respond(() =>
      http
        .expectOne(STATUS)
        .flush(
          { code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' },
          { status: 503, statusText: 'Service Unavailable' },
        ),
    );
  const tabs = () => Array.from(page.querySelectorAll<HTMLAnchorElement>('nav.tabs a'));
  const text = () => page.textContent ?? '';
  const retryButton = () => page.querySelector<HTMLButtonElement>('button.retry');

  it('queries the connection when entering and shows a loading indicator', async () => {
    await enter();

    http.expectOne(STATUS);
    expect(text()).toContain('Comprobando la conexión con RabbitMQ');
    expect(retryButton()).toBeNull();
  });

  it('does not load any area until the connection has succeeded once', async () => {
    await enter();

    expect(page.querySelector('app-exchanges-page')).toBeNull();
    http.expectOne(STATUS);
  });

  it('shows Connected and enables the operations when RabbitMQ answers', async () => {
    await enter();
    await connect();

    expect(text()).toContain('Conectado');
    expect(text()).toContain('RabbitMQ 4.4.0');
    expect(text()).not.toContain('deshabilitadas');
    expect(page.querySelector('fieldset.area')?.hasAttribute('disabled')).toBe(false);
  });

  it('redirects /rabbitmq to the exchanges area', async () => {
    await enter();
    await connect();

    expect(router.url).toBe('/rabbitmq/exchanges');
    expect(page.querySelector('app-exchanges-page')).not.toBeNull();
  });

  it('offers the five areas and navigates between all of them', async () => {
    await enter();
    await connect();

    expect(tabs().map((tab) => tab.textContent?.trim())).toEqual(AREAS);
    const expected = [
      ['/rabbitmq/exchanges', 'app-exchanges-page'],
      ['/rabbitmq/queues', 'app-queues-page'],
      ['/rabbitmq/bindings', 'app-bindings-page'],
      ['/rabbitmq/publish', 'app-publish-page'],
      ['/rabbitmq/consumers', 'app-consumers-page'],
    ];
    for (const [index, [url, selector]] of expected.entries()) {
      tabs()[index].click();
      await harness.fixture.whenStable();
      harness.detectChanges();

      expect(router.url).toBe(url);
      expect(page.querySelector(selector)).not.toBeNull();
      expect(tabs()[index].getAttribute('aria-current')).toBe('page');
    }
  });

  it('does not query the connection again when changing between areas', async () => {
    await enter();
    await connect();

    tabs()[1].click();
    await harness.fixture.whenStable();

    http.expectNone(STATUS);
  });

  it('shows the message and disables the operations when RabbitMQ is unavailable', async () => {
    await enter();
    await failWith503();

    expect(page.querySelector('.connection [role="alert"]')?.textContent).toContain('RabbitMQ no está disponible.');
    expect(text()).toContain('Las operaciones del laboratorio están deshabilitadas');
    tabs().forEach((tab) => expect(tab.getAttribute('aria-disabled')).toBe('true'));
    expect(retryButton()?.disabled).toBe(false);
  });

  it('shows the message of the backend, not RabbitMQ, when the backend does not answer', async () => {
    await enter();
    await respond(() => http.expectOne(STATUS).flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' }));

    expect(page.querySelector('.connection [role="alert"]')?.textContent).toContain('El backend no está disponible.');
    expect(page.querySelector('.connection [role="alert"]')?.textContent).not.toContain('RabbitMQ');
    expect(retryButton()).not.toBeNull();
  });

  it('does not navigate when activating a disabled tab', async () => {
    await enter();
    await failWith503();
    const urlBefore = router.url;

    tabs()[1].click();
    await harness.fixture.whenStable();

    expect(router.url).toBe(urlBefore);
  });

  it('replaces the error with Connected when the retry succeeds, disabling the button meanwhile', async () => {
    await enter();
    await failWith503();

    retryButton()!.click();
    harness.detectChanges();
    expect(retryButton()?.disabled).toBe(true);
    expect(text()).toContain('Comprobando la conexión');
    await connect();

    expect(page.querySelector('.connection [role="alert"]')).toBeNull();
    expect(text()).toContain('Conectado');
    expect(retryButton()).toBeNull();
    expect(page.querySelector('app-exchanges-page')).not.toBeNull();
    tabs().forEach((tab) => expect(tab.hasAttribute('aria-disabled')).toBe(false));
  });

  it('does not launch a second retry while one is in progress', async () => {
    await enter();
    await failWith503();

    retryButton()!.click();
    retryButton()!.click();
    harness.detectChanges();

    http.expectOne(STATUS);
    http.expectNone(STATUS);
  });

  it('keeps what is on screen but disables it when RabbitMQ drops after connecting (CA-11.1, CA-11.6)', async () => {
    await enter();
    await connect();

    TestBed.runInInjectionContext(() => undefined);
    const connection = harness.routeDebugElement!.injector.get(RabbitMqConnection);
    connection.reportFailure(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'));
    harness.detectChanges();

    expect(page.querySelector('app-exchanges-page')).not.toBeNull();
    expect(page.querySelector('fieldset.area')?.hasAttribute('disabled')).toBe(true);
    expect(page.querySelector('.connection [role="alert"]')?.textContent).toContain('RabbitMQ no está disponible.');
    expect(retryButton()).not.toBeNull();
  });

  it('announces the connection state in a polite live region', async () => {
    await enter();
    await connect();

    expect(page.querySelector('.connection')?.getAttribute('aria-live')).toBe('polite');
    expect(harness.routeDebugElement?.componentInstance).toBeInstanceOf(RabbitMqLab);
  });
});
