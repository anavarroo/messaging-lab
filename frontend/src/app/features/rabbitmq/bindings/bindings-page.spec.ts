import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangesApi } from '../exchanges/exchanges-api';
import { Queue, QueuesApi } from '../queues/queues-api';
import { Binding, BindingCreation, BindingsApi } from './bindings-api';
import { BindingsPage } from './bindings-page';

describe('BindingsPage', () => {
  const exchanges: Exchange[] = [
    { name: '', type: 'DIRECT', predefined: true },
    { name: 'amq.topic', type: 'TOPIC', predefined: true },
    { name: 'directo', type: 'DIRECT', predefined: false },
    { name: 'difusion', type: 'FANOUT', predefined: false },
    { name: 'temas', type: 'TOPIC', predefined: false },
  ];
  const queues: Queue[] = ['cola1', 'cola2'].map((name) => ({ name, ready: 0, unacknowledged: 0, total: 0 }));
  const existing: Binding[] = [
    { exchange: 'directo', queue: 'cola1', routingKey: 'alta' },
    { exchange: 'directo', queue: 'cola1', routingKey: '' },
    { exchange: 'temas', queue: 'cola2', routingKey: 'a.#' },
  ];

  let fixture: ComponentFixture<BindingsPage>;
  let page: HTMLElement;
  let list: ReturnType<typeof vi.fn<(filters?: { exchange?: string | null; queue?: string | null }) => Observable<Binding[]>>>;
  let create: ReturnType<typeof vi.fn<(e: string, q: string, k?: string | null) => Observable<BindingCreation>>>;
  let remove: ReturnType<typeof vi.fn<(e: string, q: string, k: string) => Observable<void>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    list = vi.fn(() => of(existing));
    create = vi.fn((exchange: string, queue: string, key?: string | null) =>
      of({ binding: { exchange, queue, routingKey: key ?? '' }, created: true }),
    );
    remove = vi.fn(() => of(undefined));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [BindingsPage],
      providers: [
        { provide: BindingsApi, useValue: { list, create, delete: remove } },
        { provide: ExchangesApi, useValue: { list: () => of(exchanges) } },
        { provide: QueuesApi, useValue: { list: () => of(queues) } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BindingsPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = <T extends HTMLElement>(id: string) => page.querySelector<T>(`#${id}`);
  const submitButton = () => page.querySelector<HTMLButtonElement>('form.create button[type="submit"]')!;
  const rows = () => Array.from(page.querySelectorAll('tbody tr'));
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const notice = () => page.querySelector('.notice')?.textContent?.trim();
  const optionsOf = (id: string) =>
    Array.from((el<HTMLSelectElement>(id) as HTMLSelectElement).options).map((o) => o.textContent?.trim());

  async function choose(id: string, value: string) {
    const select = el<HTMLSelectElement>(id)!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  async function type(id: string, value: string) {
    const input = el<HTMLInputElement>(id)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    await settle();
  }

  async function submit() {
    submitButton().click();
    await settle();
  }

  describe('listing', () => {
    it('shows the origin exchange, the destination queue and the routing key of each binding (CA-6.1)', () => {
      expect(rows()).toHaveLength(3);
      const cells = (index: number) => Array.from(rows()[index].querySelectorAll('td')).map((td) => td.textContent?.trim());
      expect(cells(0).slice(0, 3)).toEqual(['directo', 'cola1', 'alta']);
      expect(cells(1).slice(0, 3)).toEqual(['directo', 'cola1', '(vacía)']);
      expect(cells(2).slice(0, 3)).toEqual(['temas', 'cola2', 'a.#']);
    });

    it('says so when there are no bindings', async () => {
      list.mockReturnValue(of([]));
      page.querySelector<HTMLButtonElement>('.list-header button')!.click();
      await settle();

      expect(page.textContent).toContain('No hay bindings.');
    });

    it('filters by exchange and by queue asking the backend again', async () => {
      list.mockClear();

      await choose('filter-exchange', 'directo');
      expect(list).toHaveBeenLastCalledWith({ exchange: 'directo', queue: null });

      await choose('filter-queue', 'cola1');
      expect(list).toHaveBeenLastCalledWith({ exchange: 'directo', queue: 'cola1' });

      await choose('filter-exchange', '');
      expect(list).toHaveBeenLastCalledWith({ exchange: null, queue: 'cola1' });
    });

    it('says the filter has no results', async () => {
      list.mockReturnValue(of([]));

      await choose('filter-queue', 'cola2');

      expect(page.textContent).toContain('No hay bindings con ese filtro.');
    });

    it('keeps the list and shows the error when a reload fails (CA-11.6)', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      list.mockReturnValue(throwError(() => failure));

      page.querySelector<HTMLButtonElement>('.list-header button')!.click();
      await settle();

      expect(alerts()).toContain('RabbitMQ no está disponible.');
      expect(rows()).toHaveLength(3);
      expect(reportFailure).toHaveBeenCalledWith(failure);
    });
  });

  describe('form', () => {
    it('never offers the default exchange, but offers the others with their type (CA-6.9)', () => {
      const options = optionsOf('binding-exchange');

      expect(options).toEqual(['Elige un exchange', 'amq.topic (Topic)', 'directo (Direct)', 'difusion (Fanout)', 'temas (Topic)']);
      expect(options.some((option) => option?.startsWith('(') || option === '')).toBe(false);
    });

    it('offers the existing queues', () => {
      expect(optionsOf('binding-queue')).toEqual(['Elige una cola', 'cola1', 'cola2']);
    });

    it('shows no routing key field until an exchange is chosen', () => {
      expect(el('binding-key')).toBeNull();
    });

    it('asks for a routing key in a direct exchange', async () => {
      await choose('binding-exchange', 'directo');

      expect(page.querySelector('label[for="binding-key"]')?.textContent).toBe('Routing key');
      expect(el('binding-key-help')?.textContent).toContain('coincide exactamente');
    });

    it('hides the routing key in a fanout and says it broadcasts to all its queues (CA-6.3)', async () => {
      await choose('binding-exchange', 'difusion');

      expect(el('binding-key')).toBeNull();
      expect(page.textContent).toContain('difunde cada mensaje a todas sus colas');
    });

    it('asks for a pattern in a topic exchange and explains * and # (CA-6.4)', async () => {
      await choose('binding-exchange', 'temas');

      expect(page.querySelector('label[for="binding-key"]')?.textContent).toBe('Patrón');
      const help = el('binding-key-help')?.textContent ?? '';
      expect(help).toContain('«*» sustituye exactamente una palabra');
      expect(help).toContain('«#» sustituye cero o más palabras');
    });

    it('requires an exchange and a queue', async () => {
      await submit();

      expect(el('binding-exchange-error')?.textContent?.trim()).toBe('Elige un exchange.');
      expect(el('binding-queue-error')?.textContent?.trim()).toBe('Elige una cola.');
      expect(create).not.toHaveBeenCalled();
    });

    it.each(['a..b', '.a', 'a.', 'a*', 'x#y'])('rejects the invalid topic pattern %j without calling the backend', async (pattern) => {
      await choose('binding-exchange', 'temas');
      await choose('binding-queue', 'cola1');
      await type('binding-key', pattern);
      await submit();

      expect(el('binding-key-error')?.textContent?.trim()).not.toBe('');
      expect(el('binding-key')?.getAttribute('aria-invalid')).toBe('true');
      expect(create).not.toHaveBeenCalled();
    });

    it('rejects a routing key or pattern over 255 characters (CA-6.5)', async () => {
      await choose('binding-queue', 'cola1');
      for (const exchange of ['directo', 'temas']) {
        await choose('binding-exchange', exchange);
        await type('binding-key', 'a'.repeat(256));
        await submit();

        expect(el('binding-key-error')?.textContent).toContain('no puede superar los 255 caracteres');
      }
      expect(create).not.toHaveBeenCalled();
    });
  });

  describe('creation', () => {
    it('creates a binding in a direct exchange, announces it and reloads the list (CA-6.2)', async () => {
      list.mockClear();
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola2');
      await type('binding-key', 'baja');

      await submit();

      expect(create).toHaveBeenCalledWith('directo', 'cola2', 'baja');
      expect(notice()).toBe('Binding creado entre «directo» y «cola2».');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it.each(['a.*', 'a.#', '#', '*.b.*', ''])('creates a binding with the topic pattern %j', async (pattern) => {
      await choose('binding-exchange', 'temas');
      await choose('binding-queue', 'cola1');
      await type('binding-key', pattern);

      await submit();

      expect(create).toHaveBeenCalledWith('temas', 'cola1', pattern);
    });

    it('accepts an empty routing key in a direct exchange (CA-6.5)', async () => {
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola1');

      await submit();

      expect(create).toHaveBeenCalledWith('directo', 'cola1', '');
    });

    it('creates a fanout binding without sending any routing key', async () => {
      await choose('binding-exchange', 'directo');
      await type('binding-key', 'se-descarta-al-cambiar');
      await choose('binding-exchange', 'difusion');
      await choose('binding-queue', 'cola1');

      await submit();

      expect(create).toHaveBeenCalledWith('difusion', 'cola1', '');
    });

    it('says the binding already exists when it is repeated, without duplicating it (CA-6.7)', async () => {
      create.mockReturnValue(of({ binding: existing[0], created: false }));
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola1');
      await type('binding-key', 'alta');

      await submit();

      expect(notice()).toBe('Ese binding ya existe entre «directo» y «cola1»; no se ha duplicado.');
    });

    it('lets a queue have several bindings: the form stays usable after creating one (CA-6.6)', async () => {
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola1');
      await type('binding-key', 'una');
      await submit();
      await type('binding-key', 'otra');
      await submit();

      expect(create).toHaveBeenCalledTimes(2);
      expect(create).toHaveBeenLastCalledWith('directo', 'cola1', 'otra');
    });

    it('shows the error and reports it when the creation fails, keeping the list and the form', async () => {
      const failure = new ApiError('not-found', 'La cola «cola1» no existe.');
      create.mockReturnValue(throwError(() => failure));
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola1');
      await type('binding-key', 'alta');

      await submit();

      expect(alerts()).toContain('La cola «cola1» no existe.');
      expect(reportFailure).toHaveBeenCalledWith(failure);
      expect(rows()).toHaveLength(3);
      expect(el<HTMLInputElement>('binding-key')?.value).toBe('alta');
    });

    it('disables the button while creating and ignores repeats (CA-11.5)', async () => {
      const pending = new Subject<BindingCreation>();
      create.mockReturnValue(pending);
      await choose('binding-exchange', 'directo');
      await choose('binding-queue', 'cola1');

      await submit();
      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await settle();
      expect(create).toHaveBeenCalledTimes(1);

      pending.next({ binding: existing[0], created: true });
      await settle();
      expect(submitButton().disabled).toBe(false);
    });
  });

  describe('deletion', () => {
    const deleteButtons = () => Array.from(page.querySelectorAll<HTMLButtonElement>('tbody button.danger'));

    it('deletes without asking for confirmation and reloads the list (CA-6.8)', async () => {
      list.mockClear();

      deleteButtons()[0].click();
      await settle();

      expect(remove).toHaveBeenCalledWith('directo', 'cola1', 'alta');
      expect(page.querySelector('dialog')).toBeNull();
      expect(notice()).toBe('Binding eliminado entre «directo» y «cola1».');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('deletes the binding with an empty routing key and the one with a pattern', async () => {
      deleteButtons()[1].click();
      await settle();
      deleteButtons()[2].click();
      await settle();

      expect(remove).toHaveBeenNthCalledWith(1, 'directo', 'cola1', '');
      expect(remove).toHaveBeenNthCalledWith(2, 'temas', 'cola2', 'a.#');
    });

    it('labels each delete button with the binding it removes', () => {
      expect(deleteButtons()[1].getAttribute('aria-label')).toBe(
        'Eliminar el binding de directo a cola1 con routing key (vacía)',
      );
    });

    it('shows the error when the deletion fails', async () => {
      remove.mockReturnValue(throwError(() => new ApiError('not-found', 'El binding indicado no existe.')));

      deleteButtons()[0].click();
      await settle();

      expect(alerts()).toContain('El binding indicado no existe.');
    });

    it('disables the delete buttons while a deletion is in progress', async () => {
      const pending = new Subject<void>();
      remove.mockReturnValue(pending);

      deleteButtons()[0].click();
      await settle();

      expect(deleteButtons().every((button) => button.disabled)).toBe(true);
      pending.next();
      pending.complete();
      await settle();
      expect(deleteButtons().every((button) => !button.disabled)).toBe(true);
    });
  });
});
