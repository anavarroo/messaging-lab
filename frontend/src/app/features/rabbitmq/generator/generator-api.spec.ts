import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { REQUEST_TIMEOUT_MS } from '../../../core/http/request-timeout';
import { Emitter, GeneratorApi } from './generator-api';

describe('GeneratorApi', () => {
  const URL = '/api/rabbitmq/generator';
  const emitter: Emitter = {
    id: 'e1',
    name: 'robot',
    destination: { type: 'EXCHANGE', exchange: 'pizzeria', routingKey: 'pedido.pizza' },
    interval: { mode: 'FIXED', fixedMs: 500 },
    template: 'x',
    onlyWhenChanged: false,
    status: 'RUNNING',
    published: 3,
    unrouted: 0,
    ratePerSecond: 2,
  };

  let api: GeneratorApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(GeneratorApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('lists the emitters', () => {
    let result: Emitter[] | undefined;
    api.listEmitters().subscribe((emitters) => (result = emitters));

    const req = http.expectOne(`${URL}/emitters`);
    expect(req.request.method).toBe('GET');
    req.flush([emitter]);

    expect(result).toEqual([emitter]);
  });

  it.each(['START', 'PAUSE', 'RESUME', 'STOP'] as const)('sends the action %s and returns the new state', (action) => {
    let result: Emitter | undefined;
    api.actOnEmitter('e1', action).subscribe((value) => (result = value));

    const req = http.expectOne(`${URL}/emitters/e1/actions`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ action });
    req.flush(emitter);

    expect(result).toEqual(emitter);
  });

  it('encodes the identifier in the path', () => {
    api.actOnEmitter('a/b', 'START').subscribe();

    http.expectOne(`${URL}/emitters/a%2Fb/actions`).flush(emitter);
  });

  it('deletes an emitter', () => {
    let done = false;
    api.deleteEmitter('e1').subscribe(() => (done = true));

    const req = http.expectOne(`${URL}/emitters/e1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it('reads the settings', () => {
    api.getSettings().subscribe();

    const req = http.expectOne(`${URL}/settings`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('changes the limit with PUT', () => {
    api.updateSettings(300).subscribe();

    const req = http.expectOne(`${URL}/settings`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ maxRatePerSecond: 300 });
    req.flush({});
  });

  it('stops everything with POST', () => {
    let done = false;
    api.stopAll().subscribe(() => (done = true));

    const req = http.expectOne(`${URL}/stop-all`);
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(done).toBe(true);
  });

  it('translates a limit conflict from the backend', () => {
    let error: ApiError | undefined;
    api.updateSettings(10).subscribe({ error: (e) => (error = e) });

    http
      .expectOne(`${URL}/settings`)
      .flush(
        { code: 'CONFLICT', message: 'No se puede bajar el límite a 10 mensajes por segundo: ya hay 100 en marcha.' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(error?.kind).toBe('conflict');
    expect(error?.message).toContain('No se puede bajar el límite');
  });

  it('translates RabbitMQ being unavailable and the backend being unavailable', () => {
    const errors: ApiError[] = [];
    api.listEmitters().subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne(`${URL}/emitters`)
      .flush({ code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' }, { status: 503, statusText: 'x' });
    api.listEmitters().subscribe({ error: (e) => errors.push(e) });
    http.expectOne(`${URL}/emitters`).error(new ProgressEvent('error'));

    expect(errors.map((error) => error.kind)).toEqual(['broker-unavailable', 'backend-unavailable']);
  });

  it('fails with a timeout after 20 seconds', () => {
    vi.useFakeTimers();
    let error: ApiError | undefined;
    api.getSettings().subscribe({ error: (e) => (error = e) });
    http.expectOne(`${URL}/settings`);

    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS);

    expect(error?.kind).toBe('timeout');
  });

  describe('templates, creation and simulated consumers', () => {
    it('creates an emitter with POST and the whole request', () => {
      const request = {
        name: 'robot',
        destination: emitter.destination,
        interval: emitter.interval,
        template: 'x',
        maxMessages: null,
        maxDurationSeconds: null,
        onlyWhenChanged: false,
      };
      let result: Emitter | undefined;
      api.createEmitter(request).subscribe((created) => (result = created));

      const req = http.expectOne(`${URL}/emitters`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(emitter);
      expect(result).toEqual(emitter);
    });

    it('lists the catalog templates', () => {
      api.listTemplates().subscribe();
      const req = http.expectOne(`${URL}/templates`);
      expect(req.request.method).toBe('GET');
      req.flush([]);
    });

    it('previews a template', () => {
      let result: unknown;
      api.previewTemplate('{{numero(1, 2)}}').subscribe((preview) => (result = preview));

      const req = http.expectOne(`${URL}/templates/preview`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ template: '{{numero(1, 2)}}' });
      req.flush({ body: '1', bytes: 1 });
      expect(result).toEqual({ body: '1', bytes: 1 });
    });

    it('translates an invalid template into an error with the reason', () => {
      let error: ApiError | undefined;
      api.previewTemplate('{{foo()}}').subscribe({ error: (e) => (error = e) });

      http
        .expectOne(`${URL}/templates/preview`)
        .flush({ code: 'VALIDATION', message: 'Error en la plantilla (línea 1, columna 3): la función «foo» no existe.' }, { status: 400, statusText: 'Bad Request' });

      expect(error?.kind).toBe('validation');
      expect(error?.message).toContain('Error en la plantilla (línea 1, columna 3)');
    });

    it('lists, creates, acts on and deletes simulated consumers', () => {
      const request = {
        name: 'c',
        queue: 'cocina',
        processing: { mode: 'FIXED' as const, fixedMs: 100 },
        failurePercent: 0,
        onFailure: 'REQUEUE' as const,
      };
      api.listConsumers().subscribe();
      expect(http.expectOne(`${URL}/consumers`).request.method).toBe('GET');

      api.createConsumer(request).subscribe();
      const create = http.expectOne(`${URL}/consumers`);
      expect(create.request.method).toBe('POST');
      expect(create.request.body).toEqual(request);

      api.actOnConsumer('a/b', 'START').subscribe();
      const act = http.expectOne(`${URL}/consumers/a%2Fb/actions`);
      expect(act.request.body).toEqual({ action: 'START' });

      let done = false;
      api.deleteConsumer('a/b').subscribe(() => (done = true));
      const del = http.expectOne(`${URL}/consumers/a%2Fb`);
      expect(del.request.method).toBe('DELETE');
      del.flush(null);
      expect(done).toBe(true);
    });
  });
});
