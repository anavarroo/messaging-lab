import { Subject, TimeoutError } from 'rxjs';
import { REQUEST_TIMEOUT_MS, requestTimeout } from './request-timeout';

describe('requestTimeout', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is 20 seconds', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(20_000);
  });

  it('fails with a TimeoutError when there is no response in time', () => {
    vi.useFakeTimers();
    let error: unknown;
    new Subject<string>().pipe(requestTimeout()).subscribe({ error: (e) => (error = e) });

    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS - 1);
    expect(error).toBeUndefined();
    vi.advanceTimersByTime(1);

    expect(error).toBeInstanceOf(TimeoutError);
  });

  it('lets a timely response through', () => {
    vi.useFakeTimers();
    const source = new Subject<string>();
    const values: string[] = [];
    source.pipe(requestTimeout()).subscribe((value) => values.push(value));

    vi.advanceTimersByTime(1_000);
    source.next('respuesta');
    source.complete();
    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS);

    expect(values).toEqual(['respuesta']);
  });
});
