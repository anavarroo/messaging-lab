import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  ALL_STEPS,
  CLEANUP_STEPS,
  FANOUT_STEPS,
  GENERATOR_CLEANUP_STEPS,
  GENERATOR_STEPS,
  MAIN_STEPS,
  NAMES,
  toParts,
} from './example-content';
import { ExamplePart, GuideExample } from './guide-example';

describe('GuideExample', () => {
  const AREAS = ['exchanges', 'queues', 'bindings', 'publish', 'consumers', 'generator', 'flow'].map((a) => `/rabbitmq/${a}`);

  let fixture: ComponentFixture<GuideExample>;
  let page: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GuideExample],
      providers: [provideRouter([])],
    }).compileComponents();
    await render('pizzeria');
  });

  async function render(part: ExamplePart) {
    fixture = TestBed.createComponent(GuideExample);
    fixture.componentRef.setInput('part', part);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const articles = () => Array.from(page.querySelectorAll<HTMLElement>('article.step'));

  describe('structure (CA-4.2)', () => {
    it('shows the steps of the example it is asked for, and only those', async () => {
      expect(articles()).toHaveLength(MAIN_STEPS.length);

      await render('variante');
      expect(articles()).toHaveLength(FANOUT_STEPS.length + CLEANUP_STEPS.length);

      await render('generador');
      expect(articles()).toHaveLength(GENERATOR_STEPS.length + GENERATOR_CLEANUP_STEPS.length);
    });

    it('gives every step of every example its three parts, always in the same order', async () => {
      for (const part of ['pizzeria', 'variante', 'generador'] as const) {
        await render(part);
        articles().forEach((article) => {
          const parts = Array.from(article.querySelectorAll('h6')).map((h) => h.textContent?.trim());
          expect(parts).toEqual(['Haz esto', 'Deberías ver', 'Qué ha pasado']);
        });
      }
    });

    it('fills every part with something to do, something to compare and an explanation', () => {
      ALL_STEPS.forEach((step) => {
        expect(step.actions.length).toBeGreaterThanOrEqual(1);
        expect(step.shouldSee.length).toBeGreaterThanOrEqual(1);
        expect(step.whatHappened.trim().length).toBeGreaterThan(30);
        expect(step.title.trim().length).toBeGreaterThan(5);
      });
    });

    it('numbers the steps from 1 in each example, continuing through its cleanup', async () => {
      for (const part of ['pizzeria', 'variante', 'generador'] as const) {
        await render(part);
        const titles = articles().map((a) => a.querySelector('h5')?.textContent?.trim() ?? '');

        titles.forEach((title, index) => expect(title.startsWith(`Paso ${index + 1}. `)).toBe(true));
      }
    });

    it('labels each step by its title and uses unique ids', () => {
      const ids = articles().map((a) => a.id);
      expect(new Set(ids).size).toBe(ids.length);
      articles().forEach((article) => {
        expect(page.querySelector(`#${article.getAttribute('aria-labelledby')}`)?.textContent).toContain('Paso');
      });
    });

    it('only the variant and the generator example end with an optional cleanup', async () => {
      const groups = () => Array.from(page.querySelectorAll('h5.group')).map((h) => h.textContent?.trim());

      expect(groups()).toEqual([]);
      await render('variante');
      expect(groups()).toEqual(['Opcional: dejar el laboratorio limpio']);
      await render('generador');
      expect(groups()).toEqual(['Opcional: dejar el laboratorio limpio']);
    });

    it('explains what is going to be built and gives tips before the first step', () => {
      const text = page.textContent ?? '';

      expect(text).toContain('Lo que vamos a montar');
      expect(text).toContain('Antes de empezar');
      expect(text).toContain('Escribe los nombres exactamente como aparecen aquí');
      expect(page.querySelector('.plan')?.compareDocumentPosition(articles()[0])).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    });
  });

  describe('links to the lab (CA-4.2)', () => {
    it('sends each step to a screen that exists', () => {
      articles().forEach((article) => {
        const href = article.querySelector('a.lab-link')?.getAttribute('href');
        expect(AREAS).toContain(href);
      });
    });

    it('sends each step to the screen where its action is done', () => {
      const where = (id: string) => ALL_STEPS.find((step) => step.id === id)?.link.path;

      expect(where('crear-exchange')).toBe('/rabbitmq/exchanges');
      expect(where('cola-cocina')).toBe('/rabbitmq/queues');
      expect(where('binding-cocina')).toBe('/rabbitmq/bindings');
      expect(where('publicar-llega')).toBe('/rabbitmq/publish');
      expect(where('consumidor-cocina')).toBe('/rabbitmq/consumers');
      expect(where('inspeccionar-dlq')).toBe('/rabbitmq/queues');
    });

    it('uses link texts that match the names of the lab tabs', () => {
      const labels = new Set(ALL_STEPS.map((step) => step.link.label));
      labels.forEach((label) =>
        expect(['Exchanges', 'Colas', 'Bindings', 'Publicación', 'Consumidores', 'Generador', 'Flujo']).toContain(label),
      );
    });
  });

  describe('what it covers (CA-4.3, CA-4.4, CA-4.6)', () => {
    const ids = () => MAIN_STEPS.map((step) => step.id);
    const indexOf = (id: string) => ids().indexOf(id);

    it('creates the exchange, the queues (one with a dead-letter queue) and the bindings, in that order', () => {
      expect(indexOf('crear-exchange')).toBeGreaterThanOrEqual(0);
      expect(indexOf('cola-fallidos')).toBeLessThan(indexOf('cola-cocina'));
      expect(indexOf('cola-cocina')).toBeLessThan(indexOf('binding-cocina'));
      expect(indexOf('cola-barra')).toBeGreaterThan(indexOf('cola-cocina'));
      expect(indexOf('binding-barra')).toBeGreaterThan(indexOf('binding-cocina'));
      const kitchen = MAIN_STEPS.find((step) => step.id === 'cola-cocina')!;
      expect(kitchen.actions.join(' ')).toContain('Dead-letter queue');
    });

    it('publishes a message that arrives and one that arrives nowhere', () => {
      expect(indexOf('publicar-llega')).toBeLessThan(indexOf('publicar-no-llega'));
      const no = MAIN_STEPS.find((step) => step.id === 'publicar-no-llega')!;
      expect(no.shouldSee.join(' ')).toContain('no se ha podido enrutar a ninguna cola y se ha descartado');
    });

    it('consumes in manual mode and then requeues, confirms and rejects to the dead-letter queue', () => {
      const order = ['consumidor-cocina', 'reencolar', 'confirmar', 'publicar-otro', 'rechazar-dlq', 'inspeccionar-dlq'];
      order.forEach((id, i) => {
        expect(indexOf(id)).toBeGreaterThanOrEqual(0);
        if (i > 0) {
          expect(indexOf(id)).toBeGreaterThan(indexOf(order[i - 1]));
        }
      });
      expect(MAIN_STEPS.find((s) => s.id === 'consumidor-cocina')!.actions.join(' ')).toContain('Manual');
    });

    it('inspects the dead-letter queue showing origin and reason', () => {
      const step = MAIN_STEPS.find((s) => s.id === 'inspeccionar-dlq')!;

      expect(step.shouldSee.join(' ')).toContain('Cola de origen');
      expect(step.shouldSee.join(' ')).toContain('Motivo');
    });

    it('shows what happens to a rejected message when the queue has no dead-letter queue', () => {
      const step = MAIN_STEPS.find((s) => s.id === 'sin-dlq')!;

      expect(step.shouldSee.join(' ')).toContain('El mensaje se ha descartado');
      expect(step.actions.join(' ')).toContain(NAMES.bar);
    });

    it('stops the consumers before the fanout variant, so the counters are readable', () => {
      expect(MAIN_STEPS[MAIN_STEPS.length - 1].id).toBe('detener-consumidores');
    });

    it('has a short fanout variant: create, bind to both queues, publish without routing key (CA-4.4)', () => {
      expect(FANOUT_STEPS.map((step) => step.id)).toEqual(['fanout-crear', 'fanout-bindings', 'fanout-publicar']);
      expect(FANOUT_STEPS[0].actions.join(' ')).toContain('Fanout');
      expect(FANOUT_STEPS[1].shouldSee.join(' ')).toContain('no te pide routing key');
      expect(FANOUT_STEPS[2].shouldSee.join(' ')).toContain('Listos 1');
    });

    it('ends the variant with an optional cleanup that removes everything examples 1 and 2 created (CA-4.6)', async () => {
      expect(CLEANUP_STEPS[CLEANUP_STEPS.length - 1].id).toBe('limpiar-exchanges');
      const text = CLEANUP_STEPS.flatMap((step) => step.actions).join(' ');

      for (const name of [NAMES.kitchen, NAMES.bar, NAMES.kitchenDlq, NAMES.exchange, NAMES.fanout]) {
        expect(text).toContain(name);
      }
      await render('variante');
      expect(page.textContent).toContain('Es opcional');
    });

    it('removes the dead-letter queue last, as the cleanup advises', () => {
      const queues = CLEANUP_STEPS[0].actions[0];

      expect(queues.indexOf(NAMES.kitchen)).toBeLessThan(queues.indexOf(NAMES.kitchenDlq));
      expect(queues.indexOf(NAMES.bar)).toBeLessThan(queues.indexOf(NAMES.kitchenDlq));
    });
  });

  describe('example 3: the generator seen from the flow (CA-6.3)', () => {
    const ids = GENERATOR_STEPS.map((step) => step.id);
    const at = (id: string) => ids.indexOf(id);
    const step = (id: string) => GENERATOR_STEPS.find((s) => s.id === id)!;

    it('first builds the exchange, the queue and the binding the emitter will use, and opens the flow before anything moves', () => {
      expect(ids.slice(0, 4)).toEqual(['gen-exchange', 'gen-cola', 'gen-binding', 'gen-flujo-vacio']);
      expect(step('gen-flujo-vacio').shouldSee.join(' ')).toContain('No hay flujo activo ahora mismo');
    });

    it('creates an emitter that publishes to an exchange, using a template of the catalog, and then watches it in the flow', () => {
      const emitter = step('gen-emisor-exchange').actions.join(' ');

      expect(emitter).toContain('Plantilla del catálogo');
      expect(emitter).toContain('Usar plantilla');
      expect(emitter).toContain('A un exchange');
      expect(emitter).toContain('Iniciar');
      expect(step('gen-emisor-exchange').link.path).toBe('/rabbitmq/generator');
      expect(at('gen-ver-emisor')).toBe(at('gen-emisor-exchange') + 1);
      expect(step('gen-ver-emisor').link.path).toBe('/rabbitmq/flow');
    });

    it('creates a simulated consumer and shows the queue emptying', () => {
      expect(at('gen-consumidor')).toBeGreaterThan(at('gen-ver-emisor'));
      expect(step('gen-consumidor').actions.join(' ')).toContain('Crear consumidor');
      expect(step('gen-ver-consumidor').shouldSee.join(' ')).toContain('Entrega');
    });

    it('also publishes directly to a queue and explains the default exchange that appears', () => {
      expect(step('gen-emisor-cola').actions.join(' ')).toContain('Directamente a una cola');
      expect(step('gen-ver-cola').shouldSee.join(' ')).toContain('(exchange por defecto)');
      expect(step('gen-ver-cola').whatHappened).toContain('exchange por defecto');
    });

    it('shows a queue growing when publishing faster than consuming', () => {
      expect(step('gen-ver-cola').shouldSee.join(' ')).toContain('suben sin parar');
    });

    it('teaches the controls of the flow screen and ends by stopping everything', () => {
      const controls = step('gen-controles').actions.join(' ');

      expect(controls).toContain('Filtrar por nombre o routing key');
      expect(controls).toContain('Cámara lenta');
      expect(controls).toContain('Pausar animación');
      expect(ids[ids.length - 1]).toBe('gen-parar');
      expect(step('gen-parar').actions.join(' ')).toContain('Parar todo');
    });

    it('uses the names of the templates of the generator catalog', () => {
      expect(NAMES.temperatureEmitter).toBe('Sensor de temperatura');
      expect(NAMES.robotEmitter).toBe('Brazo robótico');
    });

    it('the cleanup removes what the example created', () => {
      const text = GENERATOR_CLEANUP_STEPS.flatMap((s) => s.actions).join(' ');

      for (const name of [NAMES.temperatureEmitter, NAMES.robotEmitter, NAMES.reader, NAMES.readings, NAMES.sensors]) {
        expect(text).toContain(name);
      }
    });

    it('is independent of examples 1 and 2: it does not use their names', () => {
      const text = [...GENERATOR_STEPS, ...GENERATOR_CLEANUP_STEPS].flatMap((s) => [...s.actions, ...s.shouldSee]).join(' ');

      for (const name of [NAMES.exchange, NAMES.kitchen, NAMES.bar, NAMES.fanout]) {
        expect(text).not.toContain(`[[${name}]]`);
      }
    });
  });

  describe('consistent names (CA-4.5)', () => {
    const allowed = new Set<string>([
      ...Object.values(NAMES),
      '1000',
      '200',
      '0',
      'En marcha',
      'Parado',
      'Directamente a una cola',
      '(exchange por defecto)',
      'planta1.#',
      'Cámara lenta (÷10)',
      'Topic',
      'Fanout',
      'Manual',
      'rejected',
      '{"pizza": "margarita", "mesa": 4}',
      '{"pizza": "cuatro quesos", "mesa": 7}',
      '{"postre": "tiramisú"}',
      '{"bebida": "agua"}',
      'Cerramos en 10 minutos',
    ]);

    const codeValues = () =>
      ALL_STEPS.flatMap((step) => [...step.actions, ...step.shouldSee]).flatMap((text) =>
        toParts(text)
          .filter((part) => part.code)
          .map((part) => part.text),
      );

    it('only uses the fixed names and values of the example', () => {
      codeValues().forEach((value) => expect(allowed).toContain(value));
    });

    it('never spells a name differently from its fixed form', () => {
      const text = ALL_STEPS.flatMap((s) => [s.title, ...s.actions, ...s.shouldSee, s.whatHappened]).join('\n');

      for (const wrong of ['pizzería»', 'pizzeria1', 'Cocina»', 'cocina_fallidos', 'cocina-fallida', 'pedido-pizza', 'pedido.pizzas']) {
        expect(text).not.toContain(wrong);
      }
    });

    it('keeps the same consumer names in every step that mentions them', () => {
      const text = ALL_STEPS.flatMap((s) => [...s.actions, ...s.shouldSee]).join('\n');

      expect(text).toContain(`[[${NAMES.cook}]]`);
      expect(text).toContain(`[[${NAMES.barista}]]`);
      expect(text).not.toMatch(/cocinero-2|barista-2/);
    });

    it('shows the values to type in code format so they can be copied', () => {
      const first = articles()[0];

      expect(Array.from(first.querySelectorAll('.actions code')).map((c) => c.textContent)).toEqual([NAMES.exchange, 'Topic']);
    });

    it('uses the right exchange type in every step that creates an exchange', () => {
      expect(ALL_STEPS.find((s) => s.id === 'crear-exchange')!.actions.join(' ')).toContain('[[Topic]]');
      expect(ALL_STEPS.find((s) => s.id === 'fanout-crear')!.actions.join(' ')).toContain('[[Fanout]]');
    });
  });

  describe('the text in the lab (RNF-3)', () => {
    const seen = () => ALL_STEPS.flatMap((step) => step.shouldSee).join('\n');

    it('quotes the notices of the lab with its own wording', () => {
      for (const notice of [
        'Exchange «pizzeria» creado.',
        'Cola «cocina-fallidos» creada.',
        'Binding creado entre «pizzeria» y «cocina».',
        'El mensaje se ha publicado y se ha enrutado al menos a una cola.',
        'Consumidor «cocinero-1» iniciado sobre la cola «cocina».',
        'Rechazado y reencolado: ha vuelto a la cola y se recibirá de nuevo marcado como reentregado.',
        'Confirmado: el mensaje se ha eliminado de la cola.',
        'Rechazado sin reencolar: el mensaje se ha enviado a la dead-letter queue de la cola.',
        'El mensaje se ha descartado: se rechazó sin reencolar y la cola no tiene dead-letter queue.',
      ]) {
        expect(seen()).toContain(notice);
      }
    });
  });

  describe('toParts', () => {
    it('keeps plain text as it is', () => {
      expect(toParts('Pulsa «Crear».')).toEqual([{ text: 'Pulsa «Crear».', code: false }]);
    });

    it('separates the marked values, in order', () => {
      expect(toParts('Escribe [[cocina]] y elige [[Topic]].')).toEqual([
        { text: 'Escribe ', code: false },
        { text: 'cocina', code: true },
        { text: ' y elige ', code: false },
        { text: 'Topic', code: true },
        { text: '.', code: false },
      ]);
    });

    it('handles a marked value at the start or the end, and brackets inside it', () => {
      expect(toParts('[[a]]')).toEqual([{ text: 'a', code: true }]);
      expect(toParts('[[{"a": [1]}]]')).toEqual([{ text: '{"a": [1]}', code: true }]);
    });

    it('returns nothing for an empty text', () => {
      expect(toParts('')).toEqual([]);
    });
  });
});
