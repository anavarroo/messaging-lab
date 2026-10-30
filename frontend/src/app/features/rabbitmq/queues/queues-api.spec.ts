import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { Queue, QueueInspection, QueuesApi } from './queues-api';

describe('QueuesApi', () => {
  const URL = '/api/rabbitmq/queues';
  const MESSAGES = '/api/rabbitmq/queues/messages';
  const queue: Queue = { name: 'pedidos', deadLetterQueue: 'fallidos', ready: 3, unacknowledged: 1, total: 4 };

  let api: QueuesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(QueuesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the queues with their counters', () => {
    let result: Queue[] | undefined;
    api.list().subscribe((queues) => (result = queues));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('GET');
    req.flush([queue]);

    expect(result).toEqual([queue]);
  });

  it('creates a queue without dead-letter queue sending only the name', () => {
    api.create('pedidos').subscribe();

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'pedidos' });
    req.flush({ queue, created: true }, { status: 201, statusText: 'Created' });
  });

  it('creates a queue with a dead-letter queue', () => {
    api.create('pedidos', 'fallidos').subscribe();

    expect(http.expectOne(URL).request.body).toEqual({ name: 'pedidos', deadLetterQueue: 'fallidos' });
  });

  it.each([null, undefined, ''])('treats %j as no dead-letter queue', (none) => {
    api.create('pedidos', none).subscribe();

    expect(http.expectOne(URL).request.body).toEqual({ name: 'pedidos' });
  });

  it('returns whether the queue was created', () => {
    const results: boolean[] = [];
    api.create('pedidos').subscribe((creation) => results.push(creation.created));
    http.expectOne(URL).flush({ queue, created: false });

    expect(results).toEqual([false]);
  });

  it('deletes a queue sending the name in the query', () => {
    let done = false;
    api.delete('a/b%c ñ').subscribe(() => (done = true));

    const req = http.expectOne(`${URL}?name=a%2Fb%25c%20%C3%B1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it('purges a queue with DELETE on its messages', () => {
    let done = false;
    api.purge('pedidos').subscribe(() => (done = true));

    const req = http.expectOne(`${MESSAGES}?queue=pedidos`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it('inspects a queue with GET on its messages', () => {
    const inspection: QueueInspection = {
      messages: [{ body: 'hola', exchange: '', routingKey: 'pedidos', redelivered: false }],
      total: 1,
      truncated: false,
    };
    let result: QueueInspection | undefined;
    api.inspect('pedidos').subscribe((value) => (result = value));

    const req = http.expectOne(`${MESSAGES}?queue=pedidos`);
    expect(req.request.method).toBe('GET');
    req.flush(inspection);

    expect(result).toEqual(inspection);
  });

  const failing: [string, () => Observable<unknown>, string, number, string][] = [
    ['create', () => api.create('x'), 'CONFLICT', 409, 'conflict'],
    ['delete', () => api.delete('x'), 'NOT_FOUND', 404, 'not-found'],
    ['purge', () => api.purge('x'), 'NOT_FOUND', 404, 'not-found'],
    ['inspect', () => api.inspect('x'), 'NOT_FOUND', 404, 'not-found'],
  ];

  it.each(failing)('translates a backend error when calling %s', (_name, call, code, status, kind) => {
    let error: ApiError | undefined;
    call().subscribe({ error: (e) => (error = e) });

    http.expectOne((req) => req.url.startsWith(URL)).flush({ code, message: 'Mensaje.' }, { status, statusText: 'x' });

    expect(error).toBeInstanceOf(ApiError);
    expect(error?.kind).toBe(kind);
    expect(error?.message).toBe('Mensaje.');
  });

  it('translates RabbitMQ being unavailable when listing', () => {
    let error: ApiError | undefined;
    api.list().subscribe({ error: (e) => (error = e) });

    http
      .expectOne(URL)
      .flush({ code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' }, { status: 503, statusText: 'x' });

    expect(error?.kind).toBe('broker-unavailable');
  });
});
