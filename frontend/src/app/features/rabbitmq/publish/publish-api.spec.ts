import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { MAX_BODY_BYTES, PublishApi } from './publish-api';

describe('PublishApi', () => {
  const URL = '/api/rabbitmq/publications';

  let api: PublishApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(PublishApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('allows 64 KB', () => {
    expect(MAX_BODY_BYTES).toBe(65536);
  });

  it('publishes with POST and returns whether the message was routed', () => {
    const results: boolean[] = [];
    api.publish('temas', 'a.b', 'hola').subscribe((result) => results.push(result.routed));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ exchange: 'temas', routingKey: 'a.b', body: 'hola' });
    req.flush({ routed: true });

    expect(results).toEqual([true]);
  });

  it('reports a message that no queue received', () => {
    let routed: boolean | undefined;
    api.publish('temas', 'a.b', 'hola').subscribe((result) => (routed = result.routed));

    http.expectOne(URL).flush({ routed: false });

    expect(routed).toBe(false);
  });

  it('publishes to the default exchange with the queue name as routing key', () => {
    api.publish('', 'pedidos', 'directo').subscribe();

    expect(http.expectOne(URL).request.body).toEqual({ exchange: '', routingKey: 'pedidos', body: 'directo' });
  });

  it.each([null, undefined, ''])('sends no routing key when it is %j', (key) => {
    api.publish('difusion', key, 'hola').subscribe();

    expect(http.expectOne(URL).request.body).toEqual({ exchange: 'difusion', body: 'hola' });
  });

  it('sends the body untouched, with markup and special characters', () => {
    const body = '<b>ñ</b> "q" \n\t😀';
    api.publish('e', 'k', body).subscribe();

    expect(http.expectOne(URL).request.body.body).toBe(body);
  });

  it('translates a missing exchange and an unavailable RabbitMQ', () => {
    const errors: ApiError[] = [];
    api.publish('x', 'k', 'b').subscribe({ error: (e) => errors.push(e) });
    http.expectOne(URL).flush({ code: 'NOT_FOUND', message: 'El exchange «x» no existe.' }, { status: 404, statusText: 'x' });
    api.publish('x', 'k', 'b').subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne(URL)
      .flush({ code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' }, { status: 503, statusText: 'x' });

    expect(errors.map((error) => error.kind)).toEqual(['not-found', 'broker-unavailable']);
    expect(errors[0].message).toBe('El exchange «x» no existe.');
  });
});
