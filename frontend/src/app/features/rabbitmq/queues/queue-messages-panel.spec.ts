import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { QueueMessagesPanel } from './queue-messages-panel';
import { QueueInspection, QueuesApi } from './queues-api';

/** Anfitrión de las pruebas del panel: le da la cola y cuenta los cierres. */
@Component({
  imports: [QueueMessagesPanel],
  template: '<app-queue-messages-panel [queue]="queue()" (closed)="closedCount.set(closedCount() + 1)" />',
})
class QueueMessagePanelHostFixture {
  readonly queue = signal('pedidos');
  readonly closedCount = signal(0);
  private readonly panel = viewChild.required(QueueMessagesPanel);

  refresh(): void {
    this.panel().refresh();
  }
}

describe('QueueMessagesPanel', () => {
  const message = (body: string, extra: Partial<QueueInspection['messages'][number]> = {}) => ({
    body,
    exchange: 'ex',
    routingKey: 'rk',
    redelivered: false,
    ...extra,
  });

  let fixture: ComponentFixture<QueueMessagePanelHostFixture>;
  let page: HTMLElement;
  let inspect: ReturnType<typeof vi.fn<(name: string) => Observable<QueueInspection>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    inspect = vi.fn(() => of({ messages: [message('uno')], total: 1, truncated: false }));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [QueueMessagePanelHostFixture],
      providers: [
        { provide: QueuesApi, useValue: { inspect } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QueueMessagePanelHostFixture);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const items = () => Array.from(page.querySelectorAll('ol.messages > li'));
  const text = () => page.textContent ?? '';

  it('reads the queue it is given and titles the panel with its name', () => {
    expect(inspect).toHaveBeenCalledWith('pedidos');
    expect(page.querySelector('h4')?.textContent).toContain('«pedidos»');
  });

  it('shows the body, exchange and routing key of each message in order', async () => {
    inspect.mockReturnValue(
      of({ messages: [message('primero'), message('segundo', { exchange: '', routingKey: '' })], total: 2, truncated: false }),
    );
    fixture.componentInstance.refresh();
    await settle();

    expect(items()).toHaveLength(2);
    expect(items()[0].querySelector('.body')?.textContent).toBe('primero');
    expect(items()[0].textContent).toContain('ex');
    expect(items()[0].textContent).toContain('rk');
    expect(items()[1].textContent).toContain('(exchange por defecto)');
    expect(items()[1].textContent).toContain('(vacía)');
    expect(text()).toContain('2 mensajes listos');
  });

  it('marks the redelivered messages', async () => {
    inspect.mockReturnValue(
      of({ messages: [message('a', { redelivered: true }), message('b')], total: 2, truncated: false }),
    );
    fixture.componentInstance.refresh();
    await settle();

    expect(items()[0].textContent).toContain('Reentregado');
    expect(items()[1].textContent).not.toContain('Reentregado');
  });

  it('warns that inspecting can mark the messages as redelivered and that pending ones are not shown', () => {
    expect(text()).toContain('puede dejarlos marcados como reentregados');
    expect(text()).toContain('pendientes de confirmar');
  });

  it('says so when the queue has no ready messages', async () => {
    inspect.mockReturnValue(of({ messages: [], total: 0, truncated: false }));
    fixture.componentInstance.refresh();
    await settle();

    expect(text()).toContain('La cola no tiene mensajes listos.');
    expect(items()).toHaveLength(0);
  });

  it('warns that only the first messages are shown and says the total when truncated', async () => {
    const fifty = Array.from({ length: 50 }, (_, i) => message(`m${i}`));
    inspect.mockReturnValue(of({ messages: fifty, total: 51, truncated: true }));
    fixture.componentInstance.refresh();
    await settle();

    expect(items()).toHaveLength(50);
    expect(text()).toContain('Solo se muestran los primeros 50 de 51 mensajes listos.');
  });

  it('shows the origin queue and the reason of a dead-letter queue message', async () => {
    inspect.mockReturnValue(
      of({
        messages: [message('x', { deathInfo: { originQueue: 'pedidos', reason: 'rejected' } })],
        total: 1,
        truncated: false,
      }),
    );
    fixture.componentInstance.refresh();
    await settle();

    expect(items()[0].textContent).toContain('Cola de origen');
    expect(items()[0].textContent).toContain('pedidos');
    expect(items()[0].textContent).toContain('Motivo');
    expect(items()[0].textContent).toContain('rejected');
  });

  it('does not show origin information for a normal message', () => {
    expect(items()[0].textContent).not.toContain('Cola de origen');
  });

  it('shows a body with a script tag as literal text, never as HTML (RNF-5)', async () => {
    const body = '<script>window.hacked = true</script><img src=x onerror="window.hacked=true">';
    inspect.mockReturnValue(of({ messages: [message(body)], total: 1, truncated: false }));
    fixture.componentInstance.refresh();
    await settle();

    expect(items()[0].querySelector('.body')?.textContent).toBe(body);
    expect(page.querySelector('ol.messages script')).toBeNull();
    expect(page.querySelector('ol.messages img')).toBeNull();
    expect((window as unknown as Record<string, unknown>)['hacked']).toBeUndefined();
  });

  it('shows the error and reports it to the connection when the reading fails', async () => {
    const failure = new ApiError('not-found', 'La cola «pedidos» no existe.');
    inspect.mockReturnValue(throwError(() => failure));
    fixture.componentInstance.refresh();
    await settle();

    expect(page.querySelector('.error[role="alert"]')?.textContent).toContain('La cola «pedidos» no existe.');
    expect(reportFailure).toHaveBeenCalledWith(failure);
  });

  it('shows a loading indicator while reading, and disables reading again', async () => {
    const pending = new Subject<QueueInspection>();
    inspect.mockReturnValue(pending);
    fixture = TestBed.createComponent(QueueMessagePanelHostFixture);
    page = fixture.nativeElement as HTMLElement;
    await settle();

    expect(text()).toContain('Leyendo los mensajes');
    expect(page.querySelector<HTMLButtonElement>('button.secondary')?.disabled).toBe(true);
    pending.next({ messages: [], total: 0, truncated: false });
    await settle();
    expect(text()).not.toContain('Leyendo los mensajes');
  });

  it('reads again when the queue changes and ignores a late answer of the previous one', async () => {
    const first = new Subject<QueueInspection>();
    const second = new Subject<QueueInspection>();
    inspect.mockReturnValueOnce(first).mockReturnValueOnce(second);
    fixture = TestBed.createComponent(QueueMessagePanelHostFixture);
    page = fixture.nativeElement as HTMLElement;
    await settle();

    fixture.componentInstance.queue.set('otra');
    await settle();
    second.next({ messages: [message('de otra')], total: 1, truncated: false });
    first.next({ messages: [message('de la anterior')], total: 1, truncated: false });
    await settle();

    expect(inspect).toHaveBeenLastCalledWith('otra');
    expect(items()).toHaveLength(1);
    expect(items()[0].textContent).toContain('de otra');
  });

  it('emits closed when the close button is pressed', async () => {
    fixture.componentInstance.closedCount.set(0);

    Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cerrar')!.click();

    expect(fixture.componentInstance.closedCount()).toBe(1);
  });
});
