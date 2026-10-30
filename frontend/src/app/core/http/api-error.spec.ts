import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError, throwError } from 'rxjs';
import {
  ApiError,
  BACKEND_UNAVAILABLE_MESSAGE,
  TIMEOUT_MESSAGE,
  UNKNOWN_MESSAGE,
  toApiError,
  translateApiErrors,
} from './api-error';

function http(status: number, body: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body, statusText: 'x' });
}

describe('toApiError', () => {
  it.each([
    ['VALIDATION', 400, 'validation'],
    ['NOT_FOUND', 404, 'not-found'],
    ['CONFLICT', 409, 'conflict'],
    ['BROKER_UNAVAILABLE', 503, 'broker-unavailable'],
    ['TIMEOUT', 504, 'timeout'],
    ['BROKER_REJECTED', 502, 'broker-rejected'],
  ])('uses the message and kind of a backend %s error', (code, status, kind) => {
    const error = toApiError(http(status, { code, message: 'Mensaje del backend.' }));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.kind).toBe(kind);
    expect(error.message).toBe('Mensaje del backend.');
  });

  it('tells RabbitMQ unavailable (backend answers) apart from the backend unavailable (proxy answers)', () => {
    const rabbit = toApiError(
      http(503, { code: 'BROKER_UNAVAILABLE', message: 'RabbitMQ no está disponible.' }),
    );
    const proxy = toApiError(http(503, '<html>Service Unavailable</html>'));

    expect(rabbit.kind).toBe('broker-unavailable');
    expect(rabbit.message).toBe('RabbitMQ no está disponible.');
    expect(proxy.kind).toBe('backend-unavailable');
    expect(proxy.message).toBe(BACKEND_UNAVAILABLE_MESSAGE);
  });

  it.each([502, 503, 504])('treats a %i without an ApiError body as the backend unavailable', (status) => {
    const error = toApiError(http(status, null));

    expect(error.kind).toBe('backend-unavailable');
    expect(error.message).toBe(BACKEND_UNAVAILABLE_MESSAGE);
  });

  it('treats status 0 (network error) as the backend unavailable', () => {
    const error = toApiError(http(0, new ProgressEvent('error')));

    expect(error.kind).toBe('backend-unavailable');
    expect(error.message).toBe(BACKEND_UNAVAILABLE_MESSAGE);
  });

  it('translates a timeout to a message saying the time ran out', () => {
    const error = toApiError(new TimeoutError());

    expect(error.kind).toBe('timeout');
    expect(error.message).toBe(TIMEOUT_MESSAGE);
    expect(error.message).toContain('tiempo');
  });

  it('never leaks technical details from an unexpected error', () => {
    const error = toApiError(http(500, 'java.lang.NullPointerException at com.messaginglab...'));

    expect(error.kind).toBe('unknown');
    expect(error.message).toBe(UNKNOWN_MESSAGE);
    expect(error.message).not.toContain('java');
  });

  it.each([
    ['an unknown code', { code: 'RARO', message: 'x' }],
    ['a missing message', { code: 'VALIDATION' }],
    ['an empty message', { code: 'VALIDATION', message: '' }],
    ['a non-object body', 'texto'],
  ])('ignores a body with %s', (_name, body) => {
    expect(toApiError(http(400, body)).message).toBe(UNKNOWN_MESSAGE);
  });

  it('keeps an ApiError as it is', () => {
    const original = new ApiError('conflict', 'Ya existe.');

    expect(toApiError(original)).toBe(original);
  });

  it.each([null, undefined, 'fallo', 42, new Error('boom')])(
    'turns an unexpected value into the generic message: %s',
    (value) => {
      expect(toApiError(value).message).toBe(UNKNOWN_MESSAGE);
    },
  );
});

describe('translateApiErrors', () => {
  it('turns the failure of the stream into an ApiError', () => {
    let error: unknown;

    throwError(() => http(0, null))
      .pipe(translateApiErrors())
      .subscribe({ error: (e) => (error = e) });

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).kind).toBe('backend-unavailable');
  });
});
