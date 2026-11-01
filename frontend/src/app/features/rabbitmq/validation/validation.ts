import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Validaciones de los formularios del laboratorio. Replican las del servidor para dar una respuesta
 * inmediata; el servidor sigue siendo la autoridad (por eso los mensajes coinciden con los suyos).
 */

export const MAX_LENGTH = 255;

const RESERVED_PREFIX = 'amq.';

const encoder = new TextEncoder();

function hasControlCharacters(value: string): boolean {
  // eslint-disable-next-line no-control-regex
  return /[\u0000-\u001f\u007f-\u009f]/.test(value);
}

function checkSize(value: string, subject: string): string | null {
  if (value.length > MAX_LENGTH) {
    return `${subject} no puede superar los ${MAX_LENGTH} caracteres.`;
  }
  if (encoder.encode(value).length > MAX_LENGTH) {
    return `${subject} no puede ocupar más de ${MAX_LENGTH} bytes en UTF-8.`;
  }
  if (hasControlCharacters(value)) {
    return `${subject} no puede contener caracteres de control.`;
  }
  return null;
}

/**
 * Valida el nombre de un exchange o una cola.
 * @param owner a quién pertenece, como aparece en el mensaje: «del exchange» o «de la cola»
 * @returns el motivo del rechazo, o `null` si es válido
 */
export function validateName(value: string | null | undefined, owner: string): string | null {
  const name = value ?? '';
  if (name.trim() === '') {
    return `El nombre ${owner} no puede estar vacío.`;
  }
  const size = checkSize(name, `El nombre ${owner}`);
  if (size) {
    return size;
  }
  if (name.startsWith(RESERVED_PREFIX)) {
    return `El nombre ${owner} no puede empezar por «${RESERVED_PREFIX}», un prefijo reservado de RabbitMQ.`;
  }
  return null;
}

/** Valida una routing key; vacía es válida. */
export function validateRoutingKey(value: string | null | undefined): string | null {
  return checkSize(value ?? '', 'La routing key');
}

/**
 * Valida el patrón de un binding en un exchange topic: palabras separadas por puntos, donde «*» y «#» son
 * una palabra completa. Vacío es válido.
 */
export function validateTopicPattern(value: string | null | undefined): string | null {
  const pattern = value ?? '';
  const size = checkSize(pattern, 'El patrón');
  if (size || pattern === '') {
    return size;
  }
  for (const word of pattern.split('.')) {
    if (word === '') {
      return 'El patrón no puede tener palabras vacías: revisa los puntos al principio, al final o repetidos.';
    }
    if ((word.includes('*') || word.includes('#')) && word !== '*' && word !== '#') {
      return `En el patrón, «*» y «#» deben ser una palabra completa, por ejemplo «a.*.b» o «a.#», y no formar parte de otra palabra como «${word}».`;
    }
  }
  return null;
}

/** Adapta una validación a un validador de formulario: el error `invalid` lleva el mensaje. */
export function asValidator(validate: (value: string | null | undefined) => string | null): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const message = validate(control.value as string | null | undefined);
    return message ? { invalid: message } : null;
  };
}

/** Mensaje de error de un control, si lo tiene. */
export function errorMessage(control: AbstractControl | null): string | null {
  const errors = control?.errors;
  return errors && typeof errors['invalid'] === 'string' ? errors['invalid'] : null;
}
