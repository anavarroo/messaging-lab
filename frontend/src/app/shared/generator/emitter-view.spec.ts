import { EMITTER_STATUS_LABELS, describeInterval, maxPerSecond } from './emitter-view';

describe('describeInterval', () => {
  it.each([
    [{ mode: 'FIXED', fixedMs: 500 }, 'cada 500 ms'],
    [{ mode: 'FIXED', fixedMs: 2 }, 'cada 2 ms'],
    [{ mode: 'FIXED', fixedMs: 1000 }, 'cada 1 s'],
    [{ mode: 'FIXED', fixedMs: 2000 }, 'cada 2 s'],
    [{ mode: 'FIXED', fixedMs: 1500 }, 'cada 1500 ms'],
    [{ mode: 'RANGE', minMs: 200, maxMs: 800 }, 'entre 200 ms y 800 ms'],
    [{ mode: 'RANGE', minMs: 3000, maxMs: 8000 }, 'entre 3 s y 8 s'],
  ] as const)('writes %j as «%s»', (interval, text) => {
    expect(describeInterval(interval)).toBe(text);
  });
});

describe('maxPerSecond', () => {
  it('uses the shortest wait', () => {
    expect(maxPerSecond({ mode: 'FIXED', fixedMs: 100 })).toBe(10);
    expect(maxPerSecond({ mode: 'RANGE', minMs: 200, maxMs: 800 })).toBe(5);
    expect(maxPerSecond({ mode: 'FIXED', fixedMs: 2 })).toBe(500);
  });

  it('is zero for an interval without values', () => {
    expect(maxPerSecond({ mode: 'FIXED' })).toBe(0);
  });
});

describe('EMITTER_STATUS_LABELS', () => {
  it('has a Spanish label for every state', () => {
    expect(Object.keys(EMITTER_STATUS_LABELS).sort()).toEqual(['FAILED', 'FINISHED', 'PAUSED', 'RUNNING', 'STOPPED']);
    expect(EMITTER_STATUS_LABELS.RUNNING).toBe('En marcha');
    expect(EMITTER_STATUS_LABELS.FINISHED).toBe('Terminado');
  });
});
