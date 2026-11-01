// Reenvía las llamadas a /api al backend local y las del asistente de IA a su servicio. Los puertos salen de
// BACKEND_PORT y ASSISTANT_PORT (cargados desde infra/.env por scripts/serve.mjs) o son 8080 y 8000 por defecto.
// La ruta más concreta va primero.
export default {
  '/api/assistant': {
    target: `http://localhost:${process.env.ASSISTANT_PORT ?? '8000'}`,
    secure: false,
  },
  '/api': {
    target: `http://localhost:${process.env.BACKEND_PORT ?? '8080'}`,
    secure: false,
  },
};
