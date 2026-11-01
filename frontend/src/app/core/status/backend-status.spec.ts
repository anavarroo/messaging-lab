import { isBackendStatus } from './backend-status';

describe('isBackendStatus', () => {
  const valid = { status: 'AVAILABLE', name: 'messaging-lab-backend', version: '0.1.0' };

  it('accepts a valid response', () => {
    expect(isBackendStatus(valid)).toBe(true);
  });

  it('accepts a valid response with extra fields', () => {
    expect(isBackendStatus({ ...valid, extra: 'ignored' })).toBe(true);
  });

  it('rejects a response without version', () => {
    const { version, ...withoutVersion } = valid;
    expect(isBackendStatus(withoutVersion)).toBe(false);
  });

  it('rejects a response without name', () => {
    const { name, ...withoutName } = valid;
    expect(isBackendStatus(withoutName)).toBe(false);
  });

  it('rejects an unknown status', () => {
    expect(isBackendStatus({ ...valid, status: 'DOWN' })).toBe(false);
  });

  it('rejects an empty name or version', () => {
    expect(isBackendStatus({ ...valid, name: '' })).toBe(false);
    expect(isBackendStatus({ ...valid, version: '' })).toBe(false);
  });

  it('rejects fields of the wrong type', () => {
    expect(isBackendStatus({ ...valid, version: 1 })).toBe(false);
    expect(isBackendStatus({ ...valid, name: null })).toBe(false);
  });

  it.each([null, undefined, 'AVAILABLE', 42, true, [], [valid]])(
    'rejects a value that is not an object: %j',
    (value) => {
      expect(isBackendStatus(value)).toBe(false);
    },
  );
});
