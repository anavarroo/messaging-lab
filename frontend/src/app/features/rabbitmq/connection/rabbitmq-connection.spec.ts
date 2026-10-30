import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { REQUEST_TIMEOUT_MS } from '../../../core/http/request-timeout';
import { RabbitMqConnection } from './rabbitmq-connection';

describe('RabbitMqConnection', () => {
  const URL = '/api/rabbitmq/status';
  const connected = { status: 'CONNECTED', version: '4.4.0' };

  let connection: RabbitMqConnection;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), RabbitMqConnection],
    });
    connection = TestBed.inject(RabbitMqConnection);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('starts loading, with the operations disabled', () => {
    expect(connection.state()).toEqual({ kind: 'loading', retrying: false });
    expect(connection.operationsEnabled()).toBe(false);
  });

  it('remembers it has connected once, even if the connection later drops', () => {
    expect(connection.hasConnected()).toBe(false);
    connection.check();
    http.expectOne(URL).flush('x', { status: 503, statusText: 'x' });
    expect(connection.hasConnected()).toBe(false);

    connection.retry();
    http.expectOne(URL).flush(connected);
    expect(connection.hasConnected()).toBe(true);

    connection.reportFailure(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'));
    expect(connection.hasConnected()).toBe(true);
  });

  it('shows the loading state while the query is in progress', () => {
    connection.check();

    expect(connection.state()).toEqual({ kind: 'loading', retrying: false });
    http.expectOne(URL);
  });

  it('is connected, with the operations enabled, when RabbitMQ answers', () => {
    connection.check();
    http.expectOne(URL).flush(connected);

    expect(connection.state()).toEqual({ kind: 'connected', version: '4.4.0' });
    expect(connection.operationsEnabled()).toBe(true);
  });

  it('reports RabbitMQ as unavailable with the message of the backend', () => {
    connection.check();
    http
      .expectOne(URL)
      .flush(
        { code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' },
        { status: 503, statusText: 'Service Unavailable' },
      );

    expect(connection.state()).toEqual({ kind: 'unavailable', message: 'RabbitMQ no está disponible.' });
    expect(connection.operationsEnabled()).toBe(false);
  });

  it('shows the message of the backend, not the one of RabbitMQ, when the backend does not answer', () => {
    connection.check();
    http.expectOne(URL).flush('Bad Gateway', { status: 502, statusText: 'Bad Gateway' });

    expect(connection.state()).toEqual({ kind: 'unavailable', message: 'El backend no está disponible.' });
  });

  it('shows the message of the backend on a network error', () => {
    connection.check();
    http.expectOne(URL).error(new ProgressEvent('error'));

    expect(connection.state()).toEqual({ kind: 'unavailable', message: 'El backend no está disponible.' });
  });

  it('reports RabbitMQ as unavailable when the query takes more than 20 seconds', () => {
    vi.useFakeTimers();
    connection.check();
    http.expectOne(URL);

    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS);

    const state = connection.state();
    expect(state.kind).toBe('unavailable');
    expect(state.kind === 'unavailable' && state.message).toContain('RabbitMQ no está disponible');
    expect(state.kind === 'unavailable' && state.message).toContain('tiempo máximo');
  });

  it('reports an unusable response as unavailable', () => {
    connection.check();
    http.expectOne(URL).flush({ status: 'CONNECTED' });

    expect(connection.state().kind).toBe('unavailable');
  });

  it('replaces the error with Connected when a retry succeeds', () => {
    connection.check();
    http.expectOne(URL).flush('x', { status: 503, statusText: 'x' });

    connection.retry();
    expect(connection.state()).toEqual({ kind: 'loading', retrying: true });
    http.expectOne(URL).flush(connected);

    expect(connection.state()).toEqual({ kind: 'connected', version: '4.4.0' });
    expect(connection.operationsEnabled()).toBe(true);
  });

  it('keeps the error after a retry that fails again', () => {
    connection.check();
    http.expectOne(URL).flush('x', { status: 503, statusText: 'x' });

    connection.retry();
    http.expectOne(URL).flush('x', { status: 503, statusText: 'x' });

    expect(connection.state().kind).toBe('unavailable');
  });

  it('ignores a retry while another one is in progress', () => {
    connection.check();
    http.expectOne(URL).flush('x', { status: 503, statusText: 'x' });

    connection.retry();
    connection.retry();
    connection.retry();

    http.expectOne(URL).flush(connected);
    expect(connection.state().kind).toBe('connected');
  });

  it('ignores a retry when nothing has failed', () => {
    connection.check();
    http.expectOne(URL).flush(connected);

    connection.retry();

    http.expectNone(URL);
    expect(connection.state().kind).toBe('connected');
  });

  describe('reportFailure', () => {
    beforeEach(() => {
      connection.check();
      http.expectOne(URL).flush(connected);
    });

    it('turns to unavailable when an operation fails because RabbitMQ is down (CA-11.1)', () => {
      connection.reportFailure(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'));

      expect(connection.state()).toEqual({ kind: 'unavailable', message: 'RabbitMQ no está disponible.' });
      expect(connection.operationsEnabled()).toBe(false);
    });

    it('turns to unavailable when the backend is down', () => {
      connection.reportFailure(new ApiError('backend-unavailable', 'El backend no está disponible.'));

      expect(connection.state()).toEqual({ kind: 'unavailable', message: 'El backend no está disponible.' });
    });

    it.each(['conflict', 'validation', 'not-found', 'broker-rejected', 'timeout', 'unknown'] as const)(
      'does not change the state for a %s failure',
      (kind) => {
        connection.reportFailure(new ApiError(kind, 'Fallo de la operación.'));

        expect(connection.state().kind).toBe('connected');
      },
    );

    it('offers a retry after an operation reported the failure', () => {
      connection.reportFailure(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'));

      connection.retry();
      http.expectOne(URL).flush(connected);

      expect(connection.state().kind).toBe('connected');
    });
  });
});
