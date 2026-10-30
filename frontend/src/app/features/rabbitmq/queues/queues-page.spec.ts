import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { QueueCreation, QueueInspection, QueuesApi, Queue } from './queues-api';
import { QueuesPage } from './queues-page';

describe('QueuesPage', () => {
  const queue = (name: string, extra: Partial<Queue> = {}): Queue => ({
    name,
    deadLetterQueue: null,
    ready: 0,
    unacknowledged: 0,
    total: 0,
    ...extra,
  });
  const initial: Queue[] = [
    queue('pedidos', { deadLetterQueue: 'fallidos', ready: 3, unacknowledged: 2, total: 5 }),
    queue('fallidos', { ready: 1, total: 1 }),
    queue('otra', { deadLetterQueue: 'fallidos' }),
  ];

  let fixture: ComponentFixture<QueuesPage>;
  let page: HTMLElement;
  let list: ReturnType<typeof vi.fn<() => Observable<Queue[]>>>;
  let create: ReturnType<typeof vi.fn<(name: string, dlq?: string | null) => Observable<QueueCreation>>>;
  let remove: ReturnType<typeof vi.fn<(name: string) => Observable<void>>>;
  let purge: ReturnType<typeof vi.fn<(name: string) => Observable<void>>>;
  let inspect: ReturnType<typeof vi.fn<(name: string) => Observable<QueueInspection>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    list = vi.fn(() => of(initial));
    create = vi.fn((name: string) => of({ queue: queue(name), created: true }));
    remove = vi.fn(() => of(undefined));
    purge = vi.fn(() => of(undefined));
    inspect = vi.fn(() => of({ messages: [], total: 0, truncated: false }));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [QueuesPage],
      providers: [
        { provide: QueuesApi, useValue: { list, create, delete: remove, purge, inspect } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QueuesPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => vi.useRealTimers());

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const input = (id: string) => page.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
  const submitButton = () => page.querySelector<HTMLButtonElement>('form button[type="submit"]')!;
  const rows = () => Array.from(page.querySelectorAll('tbody tr'));
  const rowOf = (name: string) => rows().find((row) => row.querySelector('.name')?.textContent?.trim() === name)!;
  const cells = (name: string) => Array.from(rowOf(name).querySelectorAll('td')).map((td) => td.textContent?.trim());
  const rowButton = (name: string, verb: string) =>
    rowOf(name).querySelector<HTMLButtonElement>(`button[aria-label^="${verb}"]`)!;
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const notice = () => page.querySelector('.notice')?.textContent?.trim();
  const dialog = () => page.querySelector('dialog') as HTMLDialogElement;
  const dialogButton = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;
  const refreshButton = () => page.querySelector<HTMLButtonElement>('.list-header button')!;

  async function fill(name: string, deadLetterQueue = '') {
    const nameInput = input('queue-name') as HTMLInputElement;
    nameInput.value = name;
    nameInput.dispatchEvent(new Event('input'));
    const select = input('queue-dlq') as HTMLSelectElement;
    select.value = deadLetterQueue;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  async function fillAndSubmit(name: string, deadLetterQueue = '') {
    await fill(name, deadLetterQueue);
    submitButton().click();
    await settle();
  }

  describe('listing and counters', () => {
    it('shows the name, dead-letter queue and the three counters of each queue (CA-5.1, CA-8.1)', () => {
      expect(rows()).toHaveLength(3);
      expect(cells('pedidos').slice(0, 5)).toEqual(['pedidos', 'fallidos', '3', '2', '5']);
      expect(cells('fallidos').slice(0, 5)).toEqual(['fallidos', '—', '1', '0', '1']);
    });

    it('labels the counter columns', () => {
      const headers = Array.from(page.querySelectorAll('thead th')).map((th) => th.textContent?.trim());
      expect(headers.slice(0, 5)).toEqual(['Nombre', 'Dead-letter queue', 'Listos', 'Pendientes de confirmar', 'Total']);
    });

    it('says so when there are no queues', async () => {
      list.mockReturnValue(of([]));
      refreshButton().click();
      await settle();

      expect(page.textContent).toContain('Todavía no hay colas.');
      expect(rows()).toHaveLength(0);
    });

    it('shows a loading indicator until the list arrives', async () => {
      const pending = new Subject<Queue[]>();
      list.mockReturnValue(pending);
      fixture = TestBed.createComponent(QueuesPage);
      page = fixture.nativeElement as HTMLElement;
      await settle();

      expect(page.textContent).toContain('Cargando las colas');
      pending.next(initial);
      await settle();
      expect(rows()).toHaveLength(3);
    });

    it('reloads the counters without reloading the page when pressing Update (CA-8.2)', async () => {
      list.mockClear();
      list.mockReturnValue(of([queue('pedidos', { ready: 9, total: 9 })]));

      refreshButton().click();
      await settle();

      expect(list).toHaveBeenCalledTimes(1);
      expect(cells('pedidos').slice(2, 5)).toEqual(['9', '0', '9']);
    });

    it('does not refresh the counters periodically (CA-8.9)', async () => {
      vi.useFakeTimers();
      list.mockClear();

      vi.advanceTimersByTime(10 * 60 * 1000);
      await settle();

      expect(list).not.toHaveBeenCalled();
    });

    it('keeps the previous list and shows the error when a refresh fails (CA-11.6)', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      list.mockReturnValue(throwError(() => failure));

      refreshButton().click();
      await settle();

      expect(alerts()).toContain('RabbitMQ no está disponible.');
      expect(rows()).toHaveLength(3);
      expect(reportFailure).toHaveBeenCalledWith(failure);
    });
  });

  describe('creation form', () => {
    it('offers the existing queues as dead-letter queue, plus the option of having none', () => {
      const options = Array.from((input('queue-dlq') as HTMLSelectElement).options).map((o) => o.textContent?.trim());
      expect(options).toEqual(['Sin dead-letter queue', 'pedidos', 'fallidos', 'otra']);
    });

    it('does not show errors before the user interacts', () => {
      expect(page.querySelector('#queue-name-error')?.textContent?.trim()).toBe('');
    });

    it.each([
      ['an empty name', '', 'El nombre de la cola no puede estar vacío.'],
      ['a name over 255 characters', 'a'.repeat(256), 'El nombre de la cola no puede superar los 255 caracteres.'],
      ['the reserved prefix', 'amq.mia', 'El nombre de la cola no puede empezar por «amq.»'],
    ])('rejects %s without calling the backend', async (_label, name, message) => {
      await fillAndSubmit(name);

      expect(page.querySelector('#queue-name-error')?.textContent).toContain(message);
      expect(input('queue-name').getAttribute('aria-invalid')).toBe('true');
      expect(create).not.toHaveBeenCalled();
    });

    it('rejects a queue as its own dead-letter queue (CA-5.5)', async () => {
      await fillAndSubmit('pedidos', 'pedidos');

      expect(page.querySelector('#queue-dlq-error')?.textContent).toContain(
        'Una cola no puede ser su propia dead-letter queue.',
      );
      expect(create).not.toHaveBeenCalled();
    });

    it('creates a queue without dead-letter queue, announces it and reloads the counters', async () => {
      list.mockClear();

      await fillAndSubmit('nueva');

      expect(create).toHaveBeenCalledWith('nueva', '');
      expect(notice()).toBe('Cola «nueva» creada.');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('creates a queue with a dead-letter queue chosen among the existing ones', async () => {
      await fillAndSubmit('nueva', 'fallidos');

      expect(create).toHaveBeenCalledWith('nueva', 'fallidos');
    });

    it('clears the form after creating', async () => {
      await fillAndSubmit('nueva', 'fallidos');

      expect((input('queue-name') as HTMLInputElement).value).toBe('');
      expect((input('queue-dlq') as HTMLSelectElement).value).toBe('');
    });

    it('says the queue already exists when it is identical, without duplicating it (CA-5.6)', async () => {
      create.mockReturnValue(of({ queue: queue('pedidos'), created: false }));

      await fillAndSubmit('pedidos', 'fallidos');

      expect(notice()).toBe('La cola «pedidos» ya existe con la misma configuración; no se ha duplicado.');
    });

    it('shows the difference and keeps the form when it exists with another configuration', async () => {
      create.mockReturnValue(
        throwError(
          () =>
            new ApiError(
              'conflict',
              'Ya existe con ese nombre pero con otra configuración (difiere en la dead-letter queue).',
            ),
        ),
      );

      await fillAndSubmit('pedidos');

      expect(alerts()).toContain(
        'Ya existe con ese nombre pero con otra configuración (difiere en la dead-letter queue).',
      );
      expect((input('queue-name') as HTMLInputElement).value).toBe('pedidos');
      expect(rows()).toHaveLength(3);
    });

    it('reports a failure to the connection', async () => {
      const failure = new ApiError('backend-unavailable', 'El backend no está disponible.');
      create.mockReturnValue(throwError(() => failure));

      await fillAndSubmit('nueva');

      expect(reportFailure).toHaveBeenCalledWith(failure);
    });

    it('disables the button while creating and ignores repeats (CA-11.5)', async () => {
      const pending = new Subject<QueueCreation>();
      create.mockReturnValue(pending);

      await fillAndSubmit('nueva');
      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await settle();
      expect(create).toHaveBeenCalledTimes(1);

      pending.next({ queue: queue('nueva'), created: true });
      await settle();
      expect(submitButton().disabled).toBe(false);
    });
  });

  describe('deleting', () => {
    it('asks for confirmation warning that messages and bindings are lost (CA-5.7)', async () => {
      rowButton('otra', 'Eliminar').click();
      await settle();

      expect(dialog().hasAttribute('open')).toBe(true);
      expect(dialog().textContent).toContain('Eliminar la cola «otra»');
      expect(dialog().textContent).toContain('con todos sus mensajes y sus bindings');
      expect(dialog().querySelector('li')).toBeNull();
      expect(remove).not.toHaveBeenCalled();
    });

    it('deletes only after confirming and reloads the counters', async () => {
      list.mockClear();
      rowButton('otra', 'Eliminar').click();
      await settle();

      dialogButton('Eliminar').click();
      await settle();

      expect(remove).toHaveBeenCalledWith('otra');
      expect(notice()).toBe('Cola «otra» eliminada.');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('lists the queues affected and warns about the discard when it is a dead-letter queue (CA-5.9)', async () => {
      rowButton('fallidos', 'Eliminar').click();
      await settle();

      const affected = Array.from(dialog().querySelectorAll('li')).map((li) => li.textContent?.trim());
      expect(affected).toEqual(['pedidos', 'otra']);
      expect(dialog().textContent).toContain('dead-letter queue de las colas siguientes');
      expect(dialog().textContent).toContain('se descartarán');
    });

    it('allows the deletion after confirming even when it is a dead-letter queue', async () => {
      rowButton('fallidos', 'Eliminar').click();
      await settle();

      dialogButton('Eliminar').click();
      await settle();

      expect(remove).toHaveBeenCalledWith('fallidos');
    });

    it('does not call the service when cancelling', async () => {
      rowButton('otra', 'Eliminar').click();
      await settle();

      dialogButton('Cancelar').click();
      await settle();

      expect(remove).not.toHaveBeenCalled();
      expect(notice()).toBeUndefined();
    });

    it('shows the error and keeps the list when the deletion fails', async () => {
      remove.mockReturnValue(throwError(() => new ApiError('not-found', 'La cola «otra» no existe.')));
      rowButton('otra', 'Eliminar').click();
      await settle();

      dialogButton('Eliminar').click();
      await settle();

      expect(alerts()).toContain('La cola «otra» no existe.');
      expect(rows().length).toBeGreaterThan(0);
    });

    it('disables the row actions while an operation is in progress', async () => {
      const pending = new Subject<void>();
      remove.mockReturnValue(pending);
      rowButton('otra', 'Eliminar').click();
      await settle();

      dialogButton('Eliminar').click();
      await settle();

      expect(rowButton('pedidos', 'Eliminar').disabled).toBe(true);
      expect(rowButton('pedidos', 'Vaciar').disabled).toBe(true);
      pending.next();
      pending.complete();
      await settle();
      expect(rowButton('pedidos', 'Eliminar').disabled).toBe(false);
    });
  });

  describe('purging', () => {
    it('asks for confirmation saying what is removed and what is kept (CA-5.8)', async () => {
      rowButton('pedidos', 'Vaciar').click();
      await settle();

      expect(dialog().textContent).toContain('Vaciar la cola «pedidos»');
      expect(dialog().textContent).toContain('3 mensajes listos');
      expect(dialog().textContent).toContain('La cola y sus bindings se conservan');
      expect(dialog().textContent).toContain('pendientes de confirmar por un consumidor no se tocan');
      expect(purge).not.toHaveBeenCalled();
    });

    it('purges after confirming and reloads the counters', async () => {
      list.mockClear();
      rowButton('pedidos', 'Vaciar').click();
      await settle();

      dialogButton('Vaciar').click();
      await settle();

      expect(purge).toHaveBeenCalledWith('pedidos');
      expect(notice()).toBe('Cola «pedidos» vaciada.');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('does not call the service when cancelling', async () => {
      rowButton('pedidos', 'Vaciar').click();
      await settle();

      dialogButton('Cancelar').click();
      await settle();

      expect(purge).not.toHaveBeenCalled();
    });

    it('shows the error when purging fails', async () => {
      purge.mockReturnValue(throwError(() => new ApiError('not-found', 'La cola «pedidos» no existe.')));
      rowButton('pedidos', 'Vaciar').click();
      await settle();

      dialogButton('Vaciar').click();
      await settle();

      expect(alerts()).toContain('La cola «pedidos» no existe.');
    });
  });

  describe('inspection', () => {
    it('opens the messages panel of the chosen queue', async () => {
      expect(page.querySelector('app-queue-messages-panel')).toBeNull();

      rowButton('pedidos', 'Inspeccionar').click();
      await settle();

      expect(page.querySelector('app-queue-messages-panel')).not.toBeNull();
      expect(inspect).toHaveBeenCalledWith('pedidos');
    });

    it('closes the panel from its close button', async () => {
      rowButton('pedidos', 'Inspeccionar').click();
      await settle();

      Array.from(page.querySelectorAll('app-queue-messages-panel button'))
        .find((b) => b.textContent?.trim() === 'Cerrar')!
        .dispatchEvent(new Event('click'));
      await settle();

      expect(page.querySelector('app-queue-messages-panel')).toBeNull();
    });

    it('closes the panel when the inspected queue is deleted', async () => {
      rowButton('otra', 'Inspeccionar').click();
      await settle();
      rowButton('otra', 'Eliminar').click();
      await settle();

      dialogButton('Eliminar').click();
      await settle();

      expect(page.querySelector('app-queue-messages-panel')).toBeNull();
    });

    it('reads the queue again after purging it', async () => {
      rowButton('pedidos', 'Inspeccionar').click();
      await settle();
      inspect.mockClear();
      rowButton('pedidos', 'Vaciar').click();
      await settle();

      dialogButton('Vaciar').click();
      await settle();

      expect(inspect).toHaveBeenCalledWith('pedidos');
    });
  });
});
