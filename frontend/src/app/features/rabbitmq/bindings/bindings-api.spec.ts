import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { Binding, BindingsApi } from './bindings-api';

describe('BindingsApi', () => {
  const URL = '/api/rabbitmq/bindings';
  const binding: Binding = { exchange: 'temas', queue: 'cola1', routingKey: 'a.*' };

  let api: BindingsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(BindingsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists all the bindings without filters', () => {
    let result: Binding[] | undefined;
    api.list().subscribe((bindings) => (result = bindings));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('GET');
    req.flush([binding]);

    expect(result).toEqual([binding]);
  });

  it('filters by exchange, by queue or by both', () => {
    api.list({ exchange: 'temas' }).subscribe();
    http.expectOne(`${URL}?exchange=temas`).flush([]);
    api.list({ queue: 'cola1' }).subscribe();
    http.expectOne(`${URL}?queue=cola1`).flush([]);
    api.list({ exchange: 'temas', queue: 'cola1' }).subscribe();
    http.expectOne(`${URL}?exchange=temas&queue=cola1`).flush([]);
  });

  it('ignores empty filters given as null', () => {
    api.list({ exchange: null, queue: null }).subscribe();

    http.expectOne(URL).flush([]);
  });

  it('encodes names with special characters in the filters', () => {
    api.list({ exchange: 'a/b%c', queue: 'ñ+d' }).subscribe();

    http.expectOne(`${URL}?exchange=a%2Fb%25c&queue=%C3%B1%2Bd`).flush([]);
  });

  it('creates a binding with its routing key', () => {
    let result: unknown;
    api.create('temas', 'cola1', 'a.*').subscribe((creation) => (result = creation));

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ exchange: 'temas', queue: 'cola1', routingKey: 'a.*' });
    req.flush({ binding, created: true }, { status: 201, statusText: 'Created' });

    expect(result).toEqual({ binding, created: true });
  });

  it.each([undefined, null, ''])('sends no routing key when it is %j (fanout, or empty)', (key) => {
    api.create('difusion', 'cola1', key).subscribe();

    expect(http.expectOne(URL).request.body).toEqual({ exchange: 'difusion', queue: 'cola1' });
  });

  it('reports an identical existing binding with created false', () => {
    let created: boolean | undefined;
    api.create('temas', 'cola1', 'a.*').subscribe((creation) => (created = creation.created));

    http.expectOne(URL).flush({ binding, created: false });

    expect(created).toBe(false);
  });

  it('deletes with DELETE sending exchange, queue and routing key in the query', () => {
    let done = false;
    api.delete('temas', 'cola1', 'a.#').subscribe(() => (done = true));

    const req = http.expectOne(`${URL}?exchange=temas&queue=cola1&routingKey=a.%23`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
  });

  it('deletes a binding with an empty routing key sending it empty', () => {
    api.delete('directo', 'cola1', '').subscribe();

    http.expectOne(`${URL}?exchange=directo&queue=cola1&routingKey=`).flush(null, { status: 204, statusText: 'x' });
  });

  it('translates backend errors', () => {
    const errors: ApiError[] = [];
    api.create('temas', 'x', 'a').subscribe({ error: (e) => errors.push(e) });
    http.expectOne(URL).flush({ code: 'NOT_FOUND', message: 'La cola «x» no existe.' }, { status: 404, statusText: 'x' });
    api.delete('temas', 'cola1', 'zzz').subscribe({ error: (e) => errors.push(e) });
    http
      .expectOne((req) => req.method === 'DELETE')
      .flush({ code: 'NOT_FOUND', message: 'El binding indicado no existe.' }, { status: 404, statusText: 'x' });
    api.list().subscribe({ error: (e) => errors.push(e) });
    http.expectOne(URL).error(new ProgressEvent('error'));

    expect(errors.map((error) => error.kind)).toEqual(['not-found', 'not-found', 'backend-unavailable']);
    expect(errors[0].message).toBe('La cola «x» no existe.');
  });
});
