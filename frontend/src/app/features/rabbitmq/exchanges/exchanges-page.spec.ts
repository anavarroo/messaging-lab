import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { installDialogPolyfill } from '../../../testing/dialog-polyfill';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangeCreation, ExchangesApi } from './exchanges-api';
import { ExchangesPage } from './exchanges-page';

describe('ExchangesPage', () => {
  const direct = (name: string): Exchange => ({ name, type: 'DIRECT', predefined: false });
  const defaults: Exchange[] = [
    { name: '', type: 'DIRECT', predefined: true },
    { name: 'amq.direct', type: 'DIRECT', predefined: true },
    { name: 'amq.topic', type: 'TOPIC', predefined: true },
  ];

  let fixture: ComponentFixture<ExchangesPage>;
  let page: HTMLElement;
  let list: ReturnType<typeof vi.fn<() => Observable<Exchange[]>>>;
  let create: ReturnType<typeof vi.fn<(name: string, type: string) => Observable<ExchangeCreation>>>;
  let remove: ReturnType<typeof vi.fn<(name: string) => Observable<void>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    list = vi.fn(() => of([direct('pedidos'), ...defaults]));
    create = vi.fn(() => of({ exchange: direct('nuevo'), created: true }));
    remove = vi.fn(() => of(undefined));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ExchangesPage],
      providers: [
        { provide: ExchangesApi, useValue: { list, create, delete: remove } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ExchangesPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const input = (id: string) => page.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
  const submitButton = () => page.querySelector<HTMLButtonElement>('form button[type="submit"]')!;
  const rows = () => Array.from(page.querySelectorAll('tbody tr'));
  const rowOf = (name: string) => rows().find((row) => row.querySelector('.name')?.textContent?.trim() === name)!;
  const deleteButton = (name: string) => rowOf(name).querySelector<HTMLButtonElement>('button.danger');
  const fieldError = () => page.querySelector('#exchange-name-error')?.textContent?.trim();
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const notice = () => page.querySelector('.notice')?.textContent?.trim();
  const dialog = () => page.querySelector('dialog') as HTMLDialogElement;
  const dialogButton = (label: string) =>
    Array.from(dialog().querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;

  async function fillAndSubmit(name: string, type = 'DIRECT') {
    const nameInput = input('exchange-name') as HTMLInputElement;
    nameInput.value = name;
    nameInput.dispatchEvent(new Event('input'));
    const select = input('exchange-type') as HTMLSelectElement;
    select.value = type;
    select.dispatchEvent(new Event('change'));
    submitButton().click();
    await settle();
  }

  it('lists the exchanges with their type and marks the predefined ones', () => {
    expect(rows()).toHaveLength(4);
    expect(rowOf('pedidos').textContent).toContain('Direct');
    expect(rowOf('pedidos').textContent).toContain('Creado por ti');
    expect(rowOf('amq.topic').textContent).toContain('Topic');
    expect(rowOf('amq.topic').textContent).toContain('Predefinido');
    expect(rowOf('(exchange por defecto)').textContent).toContain('Predefinido');
  });

  it('offers no delete action for the predefined exchanges (CA-4.7)', () => {
    expect(deleteButton('pedidos')).not.toBeNull();
    expect(deleteButton('amq.direct')).toBeNull();
    expect(deleteButton('amq.topic')).toBeNull();
    expect(deleteButton('(exchange por defecto)')).toBeNull();
  });

  it('shows a loading indicator until the list arrives', async () => {
    const pending = new Subject<Exchange[]>();
    list.mockReturnValue(pending);
    fixture = TestBed.createComponent(ExchangesPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();

    expect(page.textContent).toContain('Cargando los exchanges');
    pending.next([direct('a')]);
    await settle();
    expect(page.textContent).not.toContain('Cargando los exchanges');
    expect(rows()).toHaveLength(1);
  });

  it('refreshes the list on demand', async () => {
    list.mockClear();

    page.querySelector<HTMLButtonElement>('.list-header button')!.click();
    await settle();

    expect(list).toHaveBeenCalledTimes(1);
  });

  describe('form validation', () => {
    it('does not show errors before the user interacts', () => {
      expect(fieldError()).toBe('');
      expect(input('exchange-name').getAttribute('aria-invalid')).toBeNull();
    });

    it.each([
      ['an empty name', '', 'El nombre del exchange no puede estar vacío.'],
      ['a name over 255 characters', 'a'.repeat(256), 'El nombre del exchange no puede superar los 255 caracteres.'],
      ['the reserved prefix', 'amq.mio', 'El nombre del exchange no puede empezar por «amq.»'],
    ])('rejects %s without calling the backend', async (_label, name, message) => {
      await fillAndSubmit(name);

      expect(fieldError()).toContain(message);
      expect(input('exchange-name').getAttribute('aria-invalid')).toBe('true');
      expect(create).not.toHaveBeenCalled();
    });

    it('accepts names with special characters', async () => {
      await fillAndSubmit('a/b%c ñ');

      expect(create).toHaveBeenCalledWith('a/b%c ñ', 'DIRECT');
    });

    it('explains each type next to the selector', async () => {
      expect(page.querySelector('#exchange-type-help')?.textContent).toContain('coincide exactamente');
      const select = input('exchange-type') as HTMLSelectElement;
      select.value = 'FANOUT';
      select.dispatchEvent(new Event('change'));
      await settle();
      expect(page.querySelector('#exchange-type-help')?.textContent).toContain('todas las colas');
      select.value = 'TOPIC';
      select.dispatchEvent(new Event('change'));
      await settle();
      expect(page.querySelector('#exchange-type-help')?.textContent).toContain('«*»');
    });
  });

  describe('creation', () => {
    it.each(['DIRECT', 'FANOUT', 'TOPIC'])('creates a %s exchange, announces it and reloads the list', async (type) => {
      list.mockClear();

      await fillAndSubmit('eventos', type);

      expect(create).toHaveBeenCalledWith('eventos', type);
      expect(notice()).toBe('Exchange «eventos» creado.');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('clears the name after creating, keeping the chosen type', async () => {
      await fillAndSubmit('eventos', 'TOPIC');

      expect((input('exchange-name') as HTMLInputElement).value).toBe('');
      expect((input('exchange-type') as HTMLSelectElement).value).toBe('TOPIC');
      expect(fieldError()).toBe('');
    });

    it('says the exchange already exists when it is identical, without duplicating it', async () => {
      create.mockReturnValue(of({ exchange: direct('pedidos'), created: false }));

      await fillAndSubmit('pedidos');

      expect(notice()).toBe('El exchange «pedidos» ya existe con el tipo Direct; no se ha duplicado.');
    });

    it('shows the error and keeps the list and the typed name when it fails (CA-11.6)', async () => {
      create.mockReturnValue(
        throwError(() => new ApiError('conflict', 'Ya existe con ese nombre pero con otra configuración (difiere en el tipo).')),
      );

      await fillAndSubmit('pedidos', 'TOPIC');

      expect(alerts()).toContain('Ya existe con ese nombre pero con otra configuración (difiere en el tipo).');
      expect(rows()).toHaveLength(4);
      expect((input('exchange-name') as HTMLInputElement).value).toBe('pedidos');
    });

    it('reports the failure to the connection so it can offer a retry (CA-11.1)', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      create.mockReturnValue(throwError(() => failure));

      await fillAndSubmit('eventos');

      expect(reportFailure).toHaveBeenCalledWith(failure);
      expect(alerts()).toContain('RabbitMQ no está disponible.');
    });

    it('disables the create button while the operation is in progress and ignores repeats (CA-11.5)', async () => {
      const pending = new Subject<ExchangeCreation>();
      create.mockReturnValue(pending);

      await fillAndSubmit('eventos');
      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      (page.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      await settle();
      expect(create).toHaveBeenCalledTimes(1);

      pending.next({ exchange: direct('eventos'), created: true });
      await settle();
      expect(submitButton().disabled).toBe(false);
    });
  });

  describe('deletion', () => {
    async function clickDelete(name: string) {
      deleteButton(name)!.click();
      await settle();
    }

    it('asks for confirmation warning that the bindings go too (CA-4.6)', async () => {
      await clickDelete('pedidos');

      expect(dialog().hasAttribute('open')).toBe(true);
      expect(dialog().textContent).toContain('Eliminar el exchange «pedidos»');
      expect(dialog().textContent).toContain('también todos sus bindings');
      expect(remove).not.toHaveBeenCalled();
    });

    it('deletes only after confirming, then reloads the list', async () => {
      list.mockClear();
      await clickDelete('pedidos');

      dialogButton('Eliminar').click();
      await settle();

      expect(remove).toHaveBeenCalledWith('pedidos');
      expect(notice()).toBe('Exchange «pedidos» eliminado.');
      expect(list).toHaveBeenCalledTimes(1);
    });

    it('does not delete when cancelling', async () => {
      await clickDelete('pedidos');

      dialogButton('Cancelar').click();
      await settle();

      expect(remove).not.toHaveBeenCalled();
      expect(notice()).toBeUndefined();
    });

    it('shows the error and keeps the list when the deletion fails', async () => {
      remove.mockReturnValue(throwError(() => new ApiError('not-found', 'El exchange «pedidos» no existe.')));
      await clickDelete('pedidos');

      dialogButton('Eliminar').click();
      await settle();

      expect(alerts()).toContain('El exchange «pedidos» no existe.');
      expect(rows().length).toBeGreaterThan(0);
    });

    it('disables the delete buttons while the deletion is in progress', async () => {
      const pending = new Subject<void>();
      remove.mockReturnValue(pending);
      await clickDelete('pedidos');

      dialogButton('Eliminar').click();
      await settle();

      expect(deleteButton('pedidos')!.disabled).toBe(true);
      pending.next();
      pending.complete();
      await settle();
      expect(deleteButton('pedidos')!.disabled).toBe(false);
    });

    it('labels each delete button with the exchange it affects', () => {
      expect(deleteButton('pedidos')!.getAttribute('aria-label')).toBe('Eliminar el exchange pedidos');
    });
  });

  describe('loading errors', () => {
    it('shows the message and keeps the previous list when a refresh fails', async () => {
      list.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));

      page.querySelector<HTMLButtonElement>('.list-header button')!.click();
      await settle();

      expect(alerts()).toContain('RabbitMQ no está disponible.');
      expect(rows()).toHaveLength(4);
      expect(reportFailure).toHaveBeenCalled();
    });
  });
});
