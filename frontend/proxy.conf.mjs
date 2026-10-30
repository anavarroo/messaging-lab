// Reenvía las llamadas a /api al backend local. El puerto sale de BACKEND_PORT
// (cargado desde infra/.env por scripts/serve.mjs) o es 8080 por defecto.
export default {
  '/api': {
    target: `http://localhost:${process.env.BACKEND_PORT ?? '8080'}`,
    secure: false,
  },
};
