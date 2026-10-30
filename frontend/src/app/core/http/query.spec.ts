import { queryString } from './query';

describe('queryString', () => {
  it('returns an empty string when there are no parameters', () => {
    expect(queryString({})).toBe('');
  });

  it('builds a query with several parameters', () => {
    expect(queryString({ exchange: 'a', queue: 'b' })).toBe('?exchange=a&queue=b');
  });

  it('omits missing parameters', () => {
    expect(queryString({ exchange: undefined, queue: 'b', routingKey: null })).toBe('?queue=b');
    expect(queryString({ exchange: undefined })).toBe('');
  });

  it('keeps an empty value, which is meaningful (the default exchange, an empty routing key)', () => {
    expect(queryString({ name: '' })).toBe('?name=');
  });

  it('encodes the characters that would break a name', () => {
    expect(queryString({ name: 'a/b%c ñ+d&e=f#g' })).toBe('?name=a%2Fb%25c%20%C3%B1%2Bd%26e%3Df%23g');
  });
});
