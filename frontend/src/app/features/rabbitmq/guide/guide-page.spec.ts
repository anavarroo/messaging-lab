import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ELEMENTS, GLOSSARY, IDEAS, TOPIC_EXAMPLES, TROUBLES } from './guide-content';
import { GUIDE_SECTIONS, GuidePage } from './guide-page';

describe('GuidePage', () => {
  let fixture: ComponentFixture<GuidePage>;
  let page: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GuidePage],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(GuidePage);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  const indexLinks = () => Array.from(page.querySelectorAll<HTMLAnchorElement>('nav.index a'));

  it('starts with a title and an introduction for people who know nothing about RabbitMQ', () => {
    expect(page.querySelector('h3')?.textContent).toBe('Guía de RabbitMQ');
    expect(page.querySelector('.intro')?.textContent).toContain('Si nunca has usado RabbitMQ');
  });

  it('has an index at the top, labelled for assistive technology (CA-1.3)', () => {
    const nav = page.querySelector('nav.index')!;
    expect(nav.getAttribute('aria-label')).toBe('Índice de la guía');
    expect(page.querySelector('.guide > :nth-child(3)')).toBe(nav);
  });

  it('has one index link per section, in order', () => {
    expect(indexLinks().map((link) => link.textContent?.trim())).toEqual(GUIDE_SECTIONS.map((s) => s.title));
  });

  it.each(GUIDE_SECTIONS.map((section) => [section.title, section.id] as const))(
    'links «%s» to a section that exists',
    (_title, id) => {
      const link = indexLinks().find((a) => a.href.endsWith(`#${id}`));

      expect(link).toBeDefined();
      const section = page.querySelector(`section#${id}`);
      expect(section).not.toBeNull();
      expect(section?.querySelector('h4')?.textContent).toBe(GUIDE_SECTIONS.find((s) => s.id === id)?.title);
    },
  );

  it('labels every section by its title', () => {
    page.querySelectorAll('section.guide-section').forEach((section) => {
      const title = page.querySelector(`#${section.getAttribute('aria-labelledby')}`);
      expect(title?.textContent?.trim()).not.toBe('');
    });
  });

  it('uses unique ids for the sections', () => {
    const ids = GUIDE_SECTIONS.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  describe('the elements (CA-2.1)', () => {
    const names = ['Mensaje', 'Productor', 'Exchange', 'Cola', 'Binding', 'Routing key', 'Consumidor'];
    const articles = () => Array.from(page.querySelectorAll<HTMLElement>('article.element'));

    it('explains every required element, in the order a message meets them', () => {
      const titles = articles().map((article) => article.querySelector('h5')?.textContent?.trim());

      expect(titles.slice(0, 7)).toEqual(names);
      expect(titles[7]).toContain('Acknowledgement');
      expect(titles[8]).toContain('Dead-letter queue');
      expect(titles).toHaveLength(9);
    });

    it.each(ELEMENTS.map((element) => [element.name, element.id] as const))(
      'gives «%s» its four titles, always in the same order',
      (_name, id) => {
        const parts = Array.from(page.querySelectorAll(`article#${id} h6`)).map((h) => h.textContent?.trim());

        expect(parts).toEqual(['En una frase', 'Piénsalo así', 'Ejemplo', 'Dónde lo ves en el laboratorio']);
      },
    );

    it('fills every part with text', () => {
      articles().forEach((article) => {
        article.querySelectorAll('h6 + p').forEach((paragraph) => {
          expect(paragraph.textContent?.trim().length).toBeGreaterThan(15);
        });
      });
    });

    it('links each element to the lab screen where it is seen', () => {
      const hrefs = (id: string) =>
        Array.from(page.querySelectorAll<HTMLAnchorElement>(`article#${id} a.lab-link`)).map((a) => a.getAttribute('href'));

      expect(hrefs('mensaje')).toEqual(['/rabbitmq/publish']);
      expect(hrefs('productor')).toEqual(['/rabbitmq/publish']);
      expect(hrefs('exchange')).toEqual(['/rabbitmq/exchanges']);
      expect(hrefs('cola')).toEqual(['/rabbitmq/queues']);
      expect(hrefs('binding')).toEqual(['/rabbitmq/bindings']);
      expect(hrefs('routing-key')).toEqual(['/rabbitmq/publish', '/rabbitmq/bindings']);
      expect(hrefs('consumidor')).toEqual(['/rabbitmq/consumers']);
      expect(hrefs('acknowledgement')).toEqual(['/rabbitmq/consumers']);
      expect(hrefs('dead-letter-queue')).toEqual(['/rabbitmq/queues']);
    });

    it('only links to areas that exist in the lab', () => {
      const areas = ['exchanges', 'queues', 'bindings', 'publish', 'consumers'].map((a) => `/rabbitmq/${a}`);
      ELEMENTS.flatMap((element) => element.links).forEach((link) => expect(areas).toContain(link.path));
    });

    it('explains the three answers of an acknowledgement', () => {
      const text = page.querySelector('article#acknowledgement')?.textContent ?? '';

      expect(text).toContain('Confirmar');
      expect(text).toContain('Rechazar y reencolar');
      expect(text).toContain('Rechazar sin reencolar');
    });

    it('uses the same names as the lab screens: the dead-letter queue is chosen when creating a queue', () => {
      expect(page.querySelector('article#dead-letter-queue')?.textContent).toContain('Al crear una cola');
    });
  });

  describe('the exchange types (CA-2.2)', () => {
    const type = (id: string) => page.querySelector(`article#${id}`)!;

    it('explains direct, fanout and topic, each with a sentence, an example and when to use it', () => {
      for (const id of ['direct', 'fanout', 'topic']) {
        const parts = Array.from(type(id).querySelectorAll('h6')).map((h) => h.textContent?.trim());
        expect(parts).toEqual(['En una frase', 'Ejemplo', 'Cuándo usarlo']);
      }
    });

    it('explains the wildcards of the topic exchange', () => {
      const text = type('topic').textContent ?? '';

      expect(text).toContain('sustituye exactamente una palabra');
      expect(text).toContain('sustituye cero o más palabras');
    });

    it('shows a table of patterns with what each receives and does not receive', () => {
      const rows = Array.from(page.querySelectorAll('table.patterns tbody tr'));

      expect(page.querySelector('table.patterns caption')?.textContent).toContain('Qué mensajes recibe cada patrón');
      expect(rows).toHaveLength(TOPIC_EXAMPLES.length);
      expect(rows.map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(
        TOPIC_EXAMPLES.map((example) => example.pattern),
      );
    });

    it('includes the patterns of the spec: *, # and the empty-handed cases', () => {
      const patterns = TOPIC_EXAMPLES.map((example) => example.pattern);

      expect(patterns).toContain('pedido.*');
      expect(patterns).toContain('pedido.#');
      expect(patterns).toContain('#');
      expect(TOPIC_EXAMPLES.find((e) => e.pattern === 'pedido.*')?.doesNotReceive).toContain('pedido,');
    });

    it('says that RabbitMQ has more types than the lab offers', () => {
      expect(page.querySelector('#tipos-de-exchange')?.textContent).toContain('headers');
    });
  });

  describe('the diagrams (RF-3)', () => {
    const figures = () => Array.from(page.querySelectorAll<HTMLElement>('figure.diagram'));

    it('has the route of a message and one diagram per exchange type', () => {
      expect(figures()).toHaveLength(4);
      expect(page.querySelector('#recorrido figure.diagram')).not.toBeNull();
      for (const id of ['direct', 'fanout', 'topic']) {
        expect(page.querySelector(`article#${id} figure.diagram`)).not.toBeNull();
      }
    });

    it('gives every diagram a title and a description for assistive technology (CA-3.3)', () => {
      figures().forEach((figure) => {
        const svg = figure.querySelector('svg')!;
        expect(svg.getAttribute('role')).toBe('img');
        const [titleId, descId] = svg.getAttribute('aria-labelledby')!.split(' ');
        expect(svg.querySelector(`#${titleId}`)?.textContent?.trim().length).toBeGreaterThan(5);
        expect(svg.querySelector(`#${descId}`)?.textContent?.trim().length).toBeGreaterThan(40);
      });
    });

    it('also tells the same thing in visible text under each diagram (CA-3.3)', () => {
      figures().forEach((figure) => {
        expect(figure.querySelector('figcaption')?.textContent).toContain('En palabras:');
      });
    });

    it('does not depend on colour alone: every arrow of the exchange diagrams says whether it arrives', () => {
      ['direct', 'fanout', 'topic'].forEach((id) => {
        const svg = page.querySelector(`article#${id} svg`)!;
        const verdicts = Array.from(svg.querySelectorAll('.verdict')).map((v) => v.textContent?.trim());
        expect(verdicts.length).toBeGreaterThanOrEqual(2);
        verdicts.forEach((verdict) => expect(verdict).toMatch(/^(✓ llega|✗ no llega)$/));
      });
    });

    it('draws what the text says: direct delivers to one queue, fanout to both, topic to two of three', () => {
      const count = (id: string, kind: string) => page.querySelectorAll(`article#${id} svg .verdict.${kind}`).length;

      expect([count('direct', 'ok'), count('direct', 'no')]).toEqual([1, 1]);
      expect([count('fanout', 'ok'), count('fanout', 'no')]).toEqual([2, 0]);
      expect([count('topic', 'ok'), count('topic', 'no')]).toEqual([2, 1]);
    });

    it('uses unique ids so the descriptions do not get mixed up', () => {
      const ids = Array.from(page.querySelectorAll('svg [id]')).map((node) => node.id);
      expect(new Set(ids).size).toBe(ids.length);
    });
  });

  describe('ideas that confuse (CA-2.3)', () => {
    it('covers the default exchange, the unrouted message, the pending message, the redelivered one and the split between consumers', () => {
      expect(IDEAS.map((idea) => idea.id)).toEqual([
        'exchange-por-defecto',
        'mensaje-sin-cola',
        'pendiente-de-confirmar',
        'reentregado',
        'reparto',
      ]);
      IDEAS.forEach((idea) => expect(page.querySelector(`article#${idea.id} h5`)?.textContent).toBe(idea.title));
    });

    it('warns that inspecting can mark messages as redelivered, as the lab does', () => {
      expect(page.querySelector('article#reentregado')?.textContent).toContain('inspeccionar');
    });

    it('says that a message that reaches no queue is discarded, with the wording the lab uses', () => {
      expect(page.querySelector('article#mensaje-sin-cola')?.textContent).toContain('se ha descartado');
    });
  });

  describe('when something goes wrong (RF-5)', () => {
    it('covers the five usual mistakes with their causes and what to do', () => {
      expect(TROUBLES.map((trouble) => trouble.problem)).toEqual([
        'Mi mensaje no llega a la cola',
        'El patrón del topic no coincide',
        'El consumidor no recibe nada',
        'El mensaje sigue en la cola tras consumirlo',
        'Un mensaje aparece reentregado sin que yo lo haya rechazado',
      ]);
      TROUBLES.forEach((trouble) => {
        const article = page.querySelector(`article#${trouble.id}`)!;
        expect(Array.from(article.querySelectorAll('h6')).map((h) => h.textContent?.trim())).toEqual([
          'Puede ser que…',
          'Qué hacer',
        ]);
        expect(article.querySelectorAll('li').length).toBe(trouble.causes.length);
        expect(trouble.causes.length).toBeGreaterThanOrEqual(2);
      });
    });
  });

  describe('the glossary (CA-2.4)', () => {
    const terms = () => Array.from(page.querySelectorAll('dl.glossary dt')).map((dt) => dt.textContent?.trim());

    it('defines every element of CA-2.1', () => {
      for (const term of [
        'Mensaje',
        'Productor',
        'Exchange',
        'Cola',
        'Binding',
        'Routing key',
        'Consumidor',
        'Acknowledgement',
        'Dead-letter queue',
      ]) {
        expect(terms()).toContain(term);
      }
    });

    it('defines the other words used in the guide', () => {
      for (const term of [
        'Direct',
        'Fanout',
        'Topic',
        'Patrón',
        'Confirmar',
        'Rechazar y reencolar',
        'Rechazar sin reencolar',
        'Listo',
        'Pendiente de confirmar',
        'Reentregado',
        'Enrutar',
        'Exchange por defecto',
        'Persistente',
      ]) {
        expect(terms()).toContain(term);
      }
    });

    it('has a definition for each term, in alphabetical order, without repeats', () => {
      expect(page.querySelectorAll('dl.glossary dd')).toHaveLength(GLOSSARY.length);
      GLOSSARY.forEach((entry) => expect(entry.definition.trim().length).toBeGreaterThan(15));
      const names = terms() as string[];
      expect(new Set(names).size).toBe(names.length);
      expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'es')));
    });
  });

  describe('readability and accessibility (RNF-1, RNF-2)', () => {
    it('keeps a coherent heading hierarchy: one h3, the sections as h4 and never skips a level', () => {
      expect(page.querySelectorAll('h3')).toHaveLength(1);
      expect(page.querySelectorAll('h4').length).toBeGreaterThanOrEqual(GUIDE_SECTIONS.length);
      const levels = Array.from(page.querySelectorAll('h3, h4, h5, h6')).map((h) => Number(h.tagName[1]));
      levels.forEach((level, index) => {
        if (index > 0) {
          expect(level - levels[index - 1]).toBeLessThanOrEqual(1);
        }
      });
    });

    it('lets the reader jump back to the index from the end of each section', () => {
      const back = Array.from(page.querySelectorAll<HTMLAnchorElement>('p.back a'));

      expect(back.length).toBeGreaterThanOrEqual(5);
      back.forEach((link) => expect(link.href).toContain('#indice'));
      expect(page.querySelector('#indice')).not.toBeNull();
    });

    it('uses the lab words, not unexplained jargon: the guide never mentions AMQP, vhost or channels', () => {
      const text = page.textContent ?? '';

      for (const jargon of ['AMQP', 'vhost', 'virtual host', 'canal', 'channel', 'prefetch', 'QoS']) {
        expect(text).not.toContain(jargon);
      }
    });

    it('does not contain any raw HTML coming from the content', () => {
      expect(page.querySelector('script')).toBeNull();
    });
  });
});
