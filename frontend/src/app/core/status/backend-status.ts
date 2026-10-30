/** Estado del backend tal como lo define el contrato de la API (`StatusResponse`). */
export interface BackendStatus {
  status: 'AVAILABLE';
  name: string;
  version: string;
}

/** Comprueba en tiempo de ejecución que un valor recibido del backend cumple el contrato. */
export function isBackendStatus(value: unknown): value is BackendStatus {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    candidate['status'] === 'AVAILABLE' &&
    typeof candidate['name'] === 'string' &&
    candidate['name'] !== '' &&
    typeof candidate['version'] === 'string' &&
    candidate['version'] !== ''
  );
}
