import { MAX_INTERVAL_MS, MIN_INTERVAL_MS, toInterval, validateInterval } from './interval-validation';

describe('validateInterval', () => {
  it.each([
    ['FIXED', 500, null, null],
    ['FIXED', MIN_INTERVAL_MS, null, null],
    ['FIXED', MAX_INTERVAL_MS, null, null],
    ['RANGE', null, 200, 800],
    ['RANGE', null, 300, 300],
    ['RANGE', null, MIN_INTERVAL_MS, MAX_INTERVAL_MS],
  ] as const)('accepts %s %j %j %j', (mode, fixed, min, max) => {
    expect(validateInterval(mode, fixed, min, max, 'la frecuencia')).toBeNull();
  });

  it('asks for the fixed value when it is missing or not a whole number', () => {
    expect(validateInterval('FIXED', null, null, null, 'la frecuencia')).toBe(
      'Indica el valor fijo de la frecuencia, en milisegundos.',
    );
    expect(validateInterval('FIXED', 2.5, null, null, 'la frecuencia')).toContain('valor fijo');
  });

  it('asks for both ends of a range when one is missing', () => {
    expect(validateInterval('RANGE', null, 10, null, 'el tiempo de proceso')).toBe(
      'Indica el mínimo y el máximo del rango de el tiempo de proceso, en milisegundos.',
    );
    expect(validateInterval('RANGE', null, null, 10, 'la frecuencia')).toContain('mínimo y el máximo');
  });

  it('rejects too short and too long intervals with the same message as the server', () => {
    const message = 'Los intervalos deben estar entre 2 ms y 86400000 ms (un día).';

    expect(validateInterval('FIXED', 1, null, null, 'la frecuencia')).toBe(message);
    expect(validateInterval('FIXED', 0, null, null, 'la frecuencia')).toBe(message);
    expect(validateInterval('FIXED', -4, null, null, 'la frecuencia')).toBe(message);
    expect(validateInterval('FIXED', MAX_INTERVAL_MS + 1, null, null, 'la frecuencia')).toBe(message);
    expect(validateInterval('RANGE', null, 1, 10, 'la frecuencia')).toBe(message);
  });

  it('rejects a minimum greater than the maximum', () => {
    expect(validateInterval('RANGE', null, 800, 200, 'la frecuencia')).toBe(
      'El mínimo del rango (800 ms) no puede ser mayor que el máximo (200 ms).',
    );
  });
});

describe('toInterval', () => {
  it('builds a fixed or a range interval', () => {
    expect(toInterval('FIXED', 500, 1, 2)).toEqual({ mode: 'FIXED', fixedMs: 500 });
    expect(toInterval('RANGE', 500, 200, 800)).toEqual({ mode: 'RANGE', minMs: 200, maxMs: 800 });
  });
});
