import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BackendStatus } from './backend-status';
import { STATUS_TIMEOUT_MS, StatusApiService, StatusError } from './status-api.service';

describe('StatusApiService', () => {
  const valid: BackendStatus = {
    status: 'AVAILABLE',
    name: 'messaging-lab-backend',
    version: '0.1.0',
  };
  const UNAVAILABLE = 'El backend no está disponible.';
  const INVALID = 'No se ha podido obtener el estado del backend.';

  let service: StatusApiService;
  let http: HttpTestingController;
  let value: BackendStatus | undefined;
  let error: unknown;

  /** Lanza la consulta y recoge su resultado en `value` o `error`. */
  function query() {
    value = undefined;
    error = undefined;
    service.getStatus().subscribe({
      next: (status) => (value = status),
      error: (err) => (error = err),
    });
  }

  function expectStatusError(kind: StatusError['kind'], message: string) {
    expect(value).toBeUndefined();
    expect(error).toBeInstanceOf(StatusError);
    expect((error as StatusError).kind).toBe(kind);
    expect((error as StatusError).message).toBe(message);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StatusApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('requests GET /api/status', () => {
    query();
    const req = http.expectOne('/api/status');
    expect(req.request.method).toBe('GET');
    req.flush(valid);
  });

  it('returns the status when the response is valid', () => {
    query();
    http.expectOne('/api/status').flush(valid);
    expect(value).toEqual(valid);
    expect(error).toBeUndefined();
  });

  it('fails as invalid when the response is incomplete', () => {
    query();
    http.expectOne('/api/status').flush({ status: 'AVAILABLE', name: 'messaging-lab-backend' });
    expectStatusError('invalid', INVALID);
  });

  it('fails as invalid on a 500 error', () => {
    query();
    http.expectOne('/api/status').flush('error', { status: 500, statusText: 'Server Error' });
    expectStatusError('invalid', INVALID);
  });

  it.each([502, 503, 504])('fails as unavailable on a %i error from the proxy', (status) => {
    query();
    http.expectOne('/api/status').flush('error', { status, statusText: 'Gateway Error' });
    expectStatusError('unavailable', UNAVAILABLE);
  });

  it('fails as unavailable on a network error', () => {
    query();
    http.expectOne('/api/status').error(new ProgressEvent('error'));
    expectStatusError('unavailable', UNAVAILABLE);
  });

  it('fails as unavailable when the backend does not respond within 20 seconds', () => {
    vi.useFakeTimers();
    query();
    http.expectOne('/api/status');

    vi.advanceTimersByTime(STATUS_TIMEOUT_MS - 1);
    expect(error).toBeUndefined();

    vi.advanceTimersByTime(1);
    expectStatusError('unavailable', UNAVAILABLE);
  });

  it('uses a 20 second timeout', () => {
    expect(STATUS_TIMEOUT_MS).toBe(20_000);
  });
});
