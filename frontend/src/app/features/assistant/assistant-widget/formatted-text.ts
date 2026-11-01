/** Un trozo de una respuesta del asistente, con su formato. */
export interface TextSegment {
  text: string;
  format: 'plain' | 'bold' | 'italic' | 'code';
}

/** `**negrita**`, `__negrita__`, `` `código` `` y `*cursiva*`, sin cruzar saltos de línea. */
const INLINE = /\*\*([^*\n]+?)\*\*|__([^_\n]+?)__|`([^`\n]+?)`|\*([^*\s][^*\n]*?)\*/g;

/**
 * Trocea el Markdown básico que escribe el modelo para pintarlo con elementos de Angular, sin `innerHTML`: el texto
 * del modelo no es de fiar. Los títulos (`# …`) se muestran en negrita. Lo que no se reconoce (por ejemplo, un `**`
 * que aún no se ha cerrado mientras llega la respuesta) se deja tal cual.
 */
export function formatSegments(text: string): TextSegment[] {
  const segments: TextSegment[] = [];
  const add = (part: string, format: TextSegment['format']) => {
    const last = segments.at(-1);
    if (!part) {
      return;
    }
    if (last && last.format === format) {
      last.text += part;
    } else {
      segments.push({ text: part, format });
    }
  };
  text.split(/(?<=\n)/).forEach((line) => {
    const heading = /^#{1,6}\s+(.*)$/s.exec(line);
    if (heading) {
      add(heading[1].replace(/\*\*/g, ''), 'bold');
      return;
    }
    let last = 0;
    for (const match of line.matchAll(INLINE)) {
      add(line.slice(last, match.index), 'plain');
      const [, bold, underlined, code, italic] = match;
      add(bold ?? underlined ?? code ?? italic, bold || underlined ? 'bold' : code ? 'code' : 'italic');
      last = match.index + match[0].length;
    }
    add(line.slice(last), 'plain');
  });
  return segments;
}
