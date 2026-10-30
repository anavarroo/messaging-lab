// Arranca el servidor de desarrollo usando la configuración local opcional de infra/.env.
// Las variables ya definidas en el entorno tienen prioridad sobre las del archivo.
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const envFile = fileURLToPath(new URL('../../infra/.env', import.meta.url));
if (existsSync(envFile)) {
  process.loadEnvFile(envFile);
}

const port = process.env.FRONTEND_PORT ?? '4200';
const child = spawn('ng', ['serve', '--port', port, ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
child.on('exit', (code) => process.exit(code ?? 0));
