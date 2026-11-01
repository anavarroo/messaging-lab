import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LineChart } from './line-chart';

describe('LineChart', () => {
  let fixture: ComponentFixture<LineChart>;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [LineChart] }).compileComponents();
    fixture = TestBed.createComponent(LineChart);
    root = fixture.nativeElement as HTMLElement;
    fixture.componentRef.setInput('title', 'Mensajes por segundo');
    fixture.componentRef.setInput('unit', 'mensajes/s');
  });

  const set = (name: string, value: unknown) => {
    fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
  };

  it('says there is no data yet', () => {
    set('series', []);

    expect(root.querySelector('.empty')?.textContent).toContain('Todavía no hay datos.');
    expect(root.querySelector('svg')).toBeNull();
  });

  it('has a title, labelled axes with their unit and a legend', () => {
    set('series', [{ name: 'cola-a', values: [1, 2, 3] }, { name: 'cola-b', values: [3, 2, 1] }]);

    expect(root.querySelector('figcaption')?.textContent).toBe('Mensajes por segundo');
    expect(root.querySelector('.unit')?.textContent).toBe('mensajes/s');
    expect(Array.from(root.querySelectorAll('.tick')).map((t) => t.textContent?.trim())).toEqual(['5', '0', 'hace 2 min', 'ahora']);
    expect(Array.from(root.querySelectorAll('.legend li')).map((li) => li.textContent?.trim())).toEqual(['cola-a', 'cola-b']);
    expect(root.querySelector('svg')?.getAttribute('aria-label')).toContain('Mensajes por segundo (mensajes/s), últimos 2 min');
  });

  it('draws one line per series and tells them apart by their stroke, not only by their color', () => {
    set('series', [{ name: 'a', values: [1, 2] }, { name: 'b', values: [2, 1] }, { name: 'c', values: [1, 1] }]);

    const lines = Array.from(root.querySelectorAll('svg > polyline'));
    expect(lines).toHaveLength(3);
    const dashes = lines.map((line) => line.getAttribute('stroke-dasharray'));
    expect(new Set(dashes).size).toBe(3);
    expect(lines.map((line) => line.getAttribute('class'))).toEqual(['line series-0', 'line series-1', 'line series-2']);
  });

  it.each([
    [[0, 0], 1],
    [[3], 5],
    [[7], 10],
    [[12], 20],
    [[480], 500],
    [[0.4], 0.5],
  ])('rounds the top of the vertical axis for %j up to %s', (values, expected) => {
    set('series', [{ name: 'a', values }]);

    expect(Array.from(root.querySelectorAll('.tick'))[0].textContent?.trim()).toBe(String(expected).replace('.', ','));
  });

  it('puts the newest value on the right edge and leaves the left empty while there is little history', () => {
    set('series', [{ name: 'a', values: [1, 1, 1] }]);

    const points = root.querySelector('polyline')!.getAttribute('points')!.split(' ').map((p) => Number(p.split(',')[0]));
    expect(points.at(-1)).toBeCloseTo(628, 0);
    expect(points[0]).toBeGreaterThan(500);
  });

  it('keeps only the seconds of the window', () => {
    set('window', 10);
    set('series', [{ name: 'a', values: Array.from({ length: 30 }, (_, i) => i) }]);

    expect(root.querySelector('polyline')!.getAttribute('points')!.split(' ')).toHaveLength(10);
    expect(root.querySelector('tbody td')?.textContent).toBe('29');
  });

  it('offers the same data as a table with the last value, the maximum and the average', () => {
    set('series', [{ name: 'cola-a', values: [2, 4, 9, 1] }]);

    const cells = Array.from(root.querySelectorAll('tbody tr:first-child > *')).map((c) => c.textContent?.trim());
    expect(cells).toEqual(['cola-a', '1', '9', '4']);
    expect(Array.from(root.querySelectorAll('thead th')).map((th) => th.textContent?.trim())).toEqual(['Serie', 'Último', 'Máximo', 'Media']);
  });

  it('writes decimals with a comma', () => {
    set('series', [{ name: 'a', values: [0.5, 2.5] }]);

    const cells = Array.from(root.querySelectorAll('tbody td')).map((c) => c.textContent?.trim());
    expect(cells).toEqual(['2,5', '2,5', '1,5']);
  });

  it('does not use fixed colors in the svg elements: they come from variables that change with the theme', () => {
    set('series', [{ name: 'a', values: [1] }]);

    expect(root.querySelector('polyline')!.getAttribute('stroke')).toBeNull();
    expect(root.querySelector('polyline')!.getAttribute('style')).toBeNull();
  });
});
