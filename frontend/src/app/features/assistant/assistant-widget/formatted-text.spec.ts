import { formatSegments } from './formatted-text';

describe('formatSegments', () => {
  it('turns the bold the model writes into bold text', () => {
    expect(formatSegments('7. **Acciones**: dónde se hace cada cosa')).toEqual([
      { text: '7. ', format: 'plain' },
      { text: 'Acciones', format: 'bold' },
      { text: ': dónde se hace cada cosa', format: 'plain' },
    ]);
  });

  it('understands italics, code and the other bold', () => {
    expect(formatSegments('Usa `rabbitmqctl trace_on`, *solo* en __local__')).toEqual([
      { text: 'Usa ', format: 'plain' },
      { text: 'rabbitmqctl trace_on', format: 'code' },
      { text: ', ', format: 'plain' },
      { text: 'solo', format: 'italic' },
      { text: ' en ', format: 'plain' },
      { text: 'local', format: 'bold' },
    ]);
  });

  it('shows headings in bold and keeps the line breaks and lists', () => {
    expect(formatSegments('## Resumen\n- cola «cocina»\n- cola «barra»')).toEqual([
      { text: 'Resumen\n', format: 'bold' },
      { text: '- cola «cocina»\n- cola «barra»', format: 'plain' },
    ]);
  });

  it('leaves what is not closed yet as it is, while the answer is arriving', () => {
    expect(formatSegments('Hay **dos col')).toEqual([{ text: 'Hay **dos col', format: 'plain' }]);
  });

  it('does not take a list asterisk or a multiplication for italics', () => {
    expect(formatSegments('* uno\n* dos\n3 * 4 = 12')).toEqual([{ text: '* uno\n* dos\n3 * 4 = 12', format: 'plain' }]);
  });

  it('never turns the text into HTML', () => {
    expect(formatSegments('**<img src=x onerror=alert(1)>**')).toEqual([
      { text: '<img src=x onerror=alert(1)>', format: 'bold' },
    ]);
  });
});
