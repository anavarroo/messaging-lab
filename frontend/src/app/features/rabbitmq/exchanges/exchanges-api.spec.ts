import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { REQUEST_TIMEOUT_MS } from '../../../core/http/request-timeout';
import { Exchange, ExchangesApi } from './exchanges-api';

describe('ExchangesApi', () => {
  const URL = '/api/rabbitmq/exchanges';
  const topic: Exchange = { name: 'pedidos', type: 'TOPIC', predefined: false };

  let api: ExchangesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ExchangesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('lists the exchanges with GET', () => {
    let result: Exchange[] | undefined;
    api.list().subscribe((exchanges) => (result = exchanges));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('GET');
    req.flush([topic]);

    expect(result).toEqual([topic]);
  });

  it('creates an exchange with POST and returns whether it was created', () => {
    let result: unknown;
    api.create('pedidos', 'TOPIC').subscribe((creation) => (result = creation));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'pedidos', type: 'TOPIC' });
    req.flush({ exchange: topic, created: true }, { status: 201, statusText: 'Created' });

    expect(result).toEqual({ exchange: topic, created: true });
  });

  it('reports an identical existing exchange with created false', () => {
    let result: { created: boolean } | undefined;
    api.create('pedidos', 'TOPIC').subscribe((creation) => (result = creation));

    http.expectOne(URL).flush({ exchange: topic, created: false });

    expect(result?.created).toBe(false);
  });

  it('deletes with DELETE sending the name in the query', () => {
    let done = false;
    api.delete('pedidos').subscribe(() => (done = true));

    const req = http.expectOne(`${URL}?name=pedidos`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it('encodes a name with special characters in the query', () => {
    api.delete('a/b%c ñ+d').subscribe();

    http.expectOne(`${URL}?name=a%2Fb%25c%20%C3%B1%2Bd`).flush(null, { status: 204, statusText: 'x' });
  });

  it('translates a conflict from the backend to an ApiError with its message', () => {
    let error: unknown;
    api.create('pedidos', 'DIRECT').subscribe({ error: (e) => (error = e) });

    http
      .expectOne(URL)
      .flush(
        { code: 'CONFLICT', message: 'Ya existe con ese nombre pero con otra configuración (difiere en el tipo).' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).kind).toBe('conflict');
    expect((error as ApiError).message).toContain('difiere en el tipo');
  });

  it('translates a missing exchange when deleting', () => {
    let error: ApiError | undefined;
    api.delete('x').subscribe({ error: (e) => (error = e) });

    http
      .expectOne(`${URL}?name=x`)
      .flush({ code: 'NOT_FOUND', message: 'El exchange «x» no existe.' }, { status: 404, statusText: 'Not Found' });

    expect(error?.kind).toBe('not-found');
    expect(error?.message).toBe('El exchange «x» no existe.');
  });

  it('translates RabbitMQ being unavailable and the backend being unavailable differently', () => {
    const errors: ApiError[] = [];
    api.list().subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne(URL)
      .flush({ code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' }, { status: 503, statusText: 'x' });
    api.list().subscribe({ error: (e) => errors.push(e) });
    http.expectOne(URL).error(new ProgressEvent('error'));

    expect(errors.map((error) => error.kind)).toEqual(['broker-unavailable', 'backend-unavailable']);
  });

  it('fails with a timeout message after 20 seconds', () => {
    vi.useFakeTimers();
    let error: ApiError | undefined;
    api.list().subscribe({ error: (e) => (error = e) });
    http.expectOne(URL);

    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS);

    expect(error?.kind).toBe('timeout');
  });
});
