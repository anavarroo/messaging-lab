import { FormControl } from '@angular/forms';
import {
  asValidator,
  errorMessage,
  validateName,
  validateRoutingKey,
  validateTopicPattern,
} from './validation';

describe('validateName', () => {
  it.each(['pedidos', 'a', 'a/b%c ñ€', 'a'.repeat(255), 'amqp', 'x.amq.y'])('accepts %j', (name) => {
    expect(validateName(name, 'del exchange')).toBeNull();
  });

  it.each([null, undefined, '', '   '])('rejects an empty name: %j', (name) => {
    expect(validateName(name, 'de la cola')).toBe('El nombre de la cola no puede estar vacío.');
  });

  it('rejects more than 255 characters', () => {
    expect(validateName('a'.repeat(256), 'del exchange')).toBe(
      'El nombre del exchange no puede superar los 255 caracteres.',
    );
  });

  it('counts bytes in UTF-8, not only characters', () => {
    expect(validateName('ñ'.repeat(128), 'del exchange')).toContain('255 bytes en UTF-8');
    expect(validateName('ñ'.repeat(127), 'del exchange')).toBeNull();
  });

  it('rejects control characters', () => {
    expect(validateName('a\nb', 'de la cola')).toBe('El nombre de la cola no puede contener caracteres de control.');
    expect(validateName('a\u0000b', 'de la cola')).toContain('caracteres de control');
  });

  it('rejects the reserved amq. prefix', () => {
    expect(validateName('amq.mio', 'del exchange')).toBe(
      'El nombre del exchange no puede empezar por «amq.», un prefijo reservado de RabbitMQ.',
    );
  });
});

describe('validateRoutingKey', () => {
  it.each([null, undefined, '', 'a.b', 'a'.repeat(255)])('accepts %j', (key) => {
    expect(validateRoutingKey(key)).toBeNull();
  });

  it('rejects more than 255 characters and control characters', () => {
    expect(validateRoutingKey('a'.repeat(256))).toBe('La routing key no puede superar los 255 caracteres.');
    expect(validateRoutingKey('a\tb')).toContain('caracteres de control');
  });
});

describe('validateTopicPattern', () => {
  it.each(['', 'a', 'a.b', 'a.*', 'a.#', '#', '*', '*.b.*', 'a.#.z', 'pedido.creado'])('accepts %j', (pattern) => {
    expect(validateTopicPattern(pattern)).toBeNull();
  });

  it.each(['.a', 'a.', 'a..b', '.'])('rejects empty words in %j', (pattern) => {
    expect(validateTopicPattern(pattern)).toContain('palabras vacías');
  });

  it.each([
    ['a*', 'a*'],
    ['a.b#', 'b#'],
    ['x#y', 'x#y'],
    ['*a.b', '*a'],
  ])('rejects a wildcard mixed with other characters in %j', (pattern, word) => {
    expect(validateTopicPattern(pattern)).toContain(`«${word}»`);
  });

  it('rejects patterns that are too long', () => {
    expect(validateTopicPattern('a'.repeat(256))).toBe('El patrón no puede superar los 255 caracteres.');
  });
});

describe('form integration', () => {
  it('turns a validation into a validator whose message can be read back', () => {
    const control = new FormControl('', { validators: [asValidator((v) => validateName(v, 'del exchange'))] });

    expect(control.valid).toBe(false);
    expect(errorMessage(control)).toBe('El nombre del exchange no puede estar vacío.');

    control.setValue('pedidos');
    expect(control.valid).toBe(true);
    expect(errorMessage(control)).toBeNull();
  });

  it('has no message for a null control', () => {
    expect(errorMessage(null)).toBeNull();
  });
});
