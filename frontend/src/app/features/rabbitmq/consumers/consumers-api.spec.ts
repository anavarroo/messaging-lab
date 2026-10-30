import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { ConsumersApi } from './consumers-api';

describe('ConsumersApi', () => {
  const BASE = '/api/rabbitmq/sessions/s1/consumers';

  let api: ConsumersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ConsumersApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts a consumer with POST', () => {
    let result: unknown;
    api.start('s1', 'c1', 'pedidos', 'MANUAL').subscribe((consumer) => (result = consumer));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'c1', queue: 'pedidos', ackMode: 'MANUAL' });
    const consumer = { id: 'id-1', name: 'c1', queue: 'pedidos', ackMode: 'MANUAL' };
    req.flush(consumer, { status: 201, statusText: 'Created' });

    expect(result).toEqual(consumer);
  });

  it('stops a consumer with DELETE', () => {
    let done = false;
    api.stop('s1', 'id-1').subscribe(() => (done = true));

    const req = http.expectOne(`${BASE}/id-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it.each([
    ['ACK', 'CONFIRMED'],
    ['REQUEUE', 'REQUEUED'],
    ['REJECT', 'DEAD_LETTERED'],
    ['REJECT', 'DISCARDED'],
  ] as const)('resolves a message with %s and returns the outcome %s', (action, outcome) => {
    let result: string | undefined;
    api.resolve('s1', 'id-1', 7, action).subscribe((value) => (result = value));

    const req = http.expectOne(`${BASE}/id-1/messages/7`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ action });
    req.flush({ outcome });

    expect(result).toBe(outcome);
  });

  it('encodes the identifiers in the path', () => {
    api.stop('a/b', 'c d').subscribe();

    http.expectOne('/api/rabbitmq/sessions/a%2Fb/consumers/c%20d').flush(null, { status: 204, statusText: 'x' });
  });

  it('translates a double resolution to a conflict error', () => {
    let error: ApiError | undefined;
    api.resolve('s1', 'id-1', 7, 'ACK').subscribe({ error: (e) => (error = e) });

    http
      .expectOne(`${BASE}/id-1/messages/7`)
      .flush({ code: 'CONFLICT', message: 'Este mensaje ya se ha resuelto.' }, { status: 409, statusText: 'Conflict' });

    expect(error?.kind).toBe('conflict');
    expect(error?.message).toBe('Este mensaje ya se ha resuelto.');
  });

  it('translates a repeated consumer name and a missing session', () => {
    const errors: ApiError[] = [];
    api.start('s1', 'c1', 'q', 'MANUAL').subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne(BASE)
      .flush({ code: 'CONFLICT', message: 'Ya hay un consumidor con el nombre «c1» en esta sesión.' }, { status: 409, statusText: 'x' });
    api.start('s1', 'c1', 'q', 'MANUAL').subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne(BASE)
      .flush({ code: 'NOT_FOUND', message: 'La sesión no existe o ya ha terminado.' }, { status: 404, statusText: 'x' });

    expect(errors.map((error) => error.kind)).toEqual(['conflict', 'not-found']);
  });
});
