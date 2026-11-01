// El proyecto no incluye los tipos de Node: esta prueba solo lee archivos y los declara aquí lo mínimo.
// @ts-expect-error sin tipos de Node
import { readFileSync, readdirSync, statSync } from 'node:fs';
// @ts-expect-error sin tipos de Node
import { join } from 'node:path';

declare const process: { cwd(): string };

/**
 * El visualizador compartido lo reutilizan todos los laboratorios (RabbitMQ, Kafka...), así que no puede depender de
 * ninguno: no importa nada de `features/`.
 */
describe('shared/flow independence', () => {
  const root = join(process.cwd(), 'src/app/shared/flow');

  function sources(dir: string): string[] {
    return (readdirSync(dir) as string[]).flatMap((name: string) => {
      const path = join(dir, name);
      return statSync(path).isDirectory() ? sources(path) : path.endsWith('.ts') ? [path] : [];
    });
  }

  it('does not refer to anything in features/, neither imports nor styles', () => {
    const files = sources(root).filter((file) => !file.endsWith('flow-independence.spec.ts'));
    expect(files.length).toBeGreaterThan(1);
    for (const file of files) {
      const text = readFileSync(file, 'utf8') as string;
      // cualquier referencia, también las de hojas de estilo, a la carpeta de los laboratorios
      const references = text.split('\n').filter((line: string) => /features\//.test(line));
      expect(references, file).toEqual([]);
    }
  });

  it('really notices an import from features/', () => {
    expect(["import { X } from '../../features/rabbitmq/x';"].filter((line) => /features\//.test(line))).toHaveLength(1);
  });
});
