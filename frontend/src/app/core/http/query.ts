/**
 * Construye la cadena de consulta (`?a=1&b=2`) codificando cada valor con `encodeURIComponent`. Los valores
 * ausentes se omiten. No se usa `HttpParams` porque deja sin codificar caracteres como `+`, que el servidor
 * leería como un espacio y rompería nombres como «a+b».
 */
export function queryString(params: Record<string, string | null | undefined>): string {
  const parts = Object.entries(params)
    .filter((entry): entry is [string, string] => entry[1] !== null && entry[1] !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length === 0 ? '' : `?${parts.join('&')}`;
}
