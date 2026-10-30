import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../../../../core/http/api-error';
import { FakeEventSource } from '../../../../testing/fake-event-source';
import { RabbitMqConnection } from '../../connection/rabbitmq-connection';
import { Queue, QueuesApi } from '../../queues/queues-api';
import { ActionOutcome, ConsumerInfo, ConsumersApi } from '../consumers-api';
import { ConsumerSessionService } from '../consumer-session.service';
import { EVENT_SOURCE_FACTORY } from '../event-source-factory';
import { ConsumersPage } from './consumers-page';

describe('ConsumersPage', () => {
  const queues: Queue[] = ['pedidos', 'fallidos'].map((name) => ({ name, ready: 0, unacknowledged: 0, total: 0 }));

  let fixture: ComponentFixture<ConsumersPage>;
  let page: HTMLElement;
  let sources: FakeEventSource[];
  let startApi: ReturnType<typeof vi.fn<(s: string, n: string, q: string, m: 'AUTOMATIC' | 'MANUAL') => Observable<ConsumerInfo>>>;
  let stopApi: ReturnType<typeof vi.fn<(s: string, c: string) => Observable<void>>>;
  let resolveApi: ReturnType<typeof vi.fn<(s: string, c: string, t: number, a: string) => Observable<ActionOutcome>>>;
  let listQueues: ReturnType<typeof vi.fn<() => Observable<Queue[]>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    sources = [];
    startApi = vi.fn((_s, name, queue, mode) => of({ id: `id-${name}`, name, queue, ackMode: mode }));
    stopApi = vi.fn(() => of(undefined));
    resolveApi = vi.fn(() => of('CONFIRMED' as ActionOutcome));
    listQueues = vi.fn(() => of(queues));
    reportFailure = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ConsumersPage],
      providers: [
        ConsumerSessionService,
        { provide: ConsumersApi, useValue: { start: startApi, stop: stopApi, resolve: resolveApi } },
        { provide: QueuesApi, useValue: { list: listQueues } },
        { provide: RabbitMqConnection, useValue: { reportFailure } },
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            const source = new FakeEventSource(url);
            sources.push(source);
            return source;
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ConsumersPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = <T extends HTMLElement>(selector: string) => page.querySelector<T>(selector);
  const last = () => sources[sources.length - 1];
  const submitButton = () => el<HTMLButtonElement>('form button[type="submit"]')!;
  const consumers = () => Array.from(page.querySelectorAll('article.consumer'));
  const logOf = (name: string) => page.querySelector(`[role="log"][aria-label^="Mensajes recibidos por ${name},"]`)!;
  const messagesOf = (name: string) => Array.from(logOf(name).querySelectorAll('.message'));
  const alerts = () => Array.from(page.querySelectorAll('.error[role="alert"]')).map((e) => e.textContent?.trim());
  const buttonIn = (root: Element, label: string) =>
    Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;

  async function setForm(queue: string, mode: 'MANUAL' | 'AUTOMATIC', name?: string) {
    const select = el<HTMLSelectElement>('#consumer-queue')!;
    select.value = queue;
    select.dispatchEvent(new Event('change'));
    const radio = page.querySelector<HTMLInputElement>(`input[type="radio"][value="${mode}"]`)!;
    radio.checked = true;
    radio.dispatchEvent(new Event('change'));
    if (name !== undefined) {
      const input = el<HTMLInputElement>('#consumer-name')!;
      input.value = name;
      input.dispatchEvent(new Event('input'));
    }
    await settle();
  }

  /** Rellena el formulario, lo envía y responde `session-started` si hace falta abrir el flujo. */
  async function startConsumer(queue: string, mode: 'MANUAL' | 'AUTOMATIC', name?: string) {
    await setForm(queue, mode, name);
    submitButton().click();
    await Promise.resolve();
    if (sources.length > 0 && !last().closed && fixtureNeedsSession()) {
      last().emit('session-started', { sessionId: 's1' });
    }
    await settle();
  }

  let sessionAnnounced = new Set<FakeEventSource>();
  function fixtureNeedsSession(): boolean {
    if (sessionAnnounced.has(last())) {
      return false;
    }
    sessionAnnounced.add(last());
    return true;
  }
  beforeEach(() => (sessionAnnounced = new Set()));

  describe('form', () => {
    it('offers the existing queues', () => {
      const options = Array.from(el<HTMLSelectElement>('#consumer-queue')!.options).map((o) => o.textContent?.trim());
      expect(options).toEqual(['Elige una cola', 'pedidos', 'fallidos']);
    });

    it('suggests a name for the consumer', () => {
      expect(el<HTMLInputElement>('#consumer-name')!.value).toBe('consumidor-1');
    });

    it('offers both acknowledgement modes, manual by default', () => {
      const manual = page.querySelector<HTMLInputElement>('input[value="MANUAL"]')!;
      const automatic = page.querySelector<HTMLInputElement>('input[value="AUTOMATIC"]')!;
      expect(manual.checked).toBe(true);
      expect(automatic.checked).toBe(false);
      expect(page.textContent).toContain('confirma cada mensaje al recibirlo');
    });

    it('requires a queue', async () => {
      submitButton().click();
      await settle();

      expect(el('#consumer-queue-error')?.textContent?.trim()).toBe('Elige la cola que consumir.');
      expect(startApi).not.toHaveBeenCalled();
      expect(sources).toHaveLength(0);
    });

    it.each([
      ['empty', '', 'El nombre del consumidor no puede estar vacío.'],
      ['too long', 'a'.repeat(256), 'no puede superar los 255 caracteres'],
      ['with control characters', 'a\tb', 'caracteres de control'],
    ])('rejects a name that is %s', async (_label, name, message) => {
      await setForm('pedidos', 'MANUAL', name);
      submitButton().click();
      await settle();

      expect(el('#consumer-name-error')?.textContent).toContain(message);
      expect(startApi).not.toHaveBeenCalled();
    });

    it('does not open the stream until a consumer is created', () => {
      expect(sources).toHaveLength(0);
    });
  });

  describe('starting consumers', () => {
    it('starts a manual consumer, opens the stream and lists it as active (CA-9.1)', async () => {
      await startConsumer('pedidos', 'MANUAL', 'mi-consumidor');

      expect(sources).toHaveLength(1);
      expect(startApi).toHaveBeenCalledWith('s1', 'mi-consumidor', 'pedidos', 'MANUAL');
      expect(consumers()).toHaveLength(1);
      expect(consumers()[0].textContent).toContain('mi-consumidor');
      expect(consumers()[0].textContent).toContain('Cola «pedidos»');
      expect(consumers()[0].textContent).toContain('Manual');
      expect(consumers()[0].textContent).toContain('Activo');
      expect(el('.notice')?.textContent).toContain('Consumidor «mi-consumidor» iniciado');
    });

    it('starts an automatic consumer', async () => {
      await startConsumer('pedidos', 'AUTOMATIC', 'auto');

      expect(startApi).toHaveBeenCalledWith('s1', 'auto', 'pedidos', 'AUTOMATIC');
      expect(consumers()[0].textContent).toContain('Automático');
    });

    it('allows several consumers on the same queue and suggests a new name for the next (CA-9.11)', async () => {
      await startConsumer('pedidos', 'MANUAL');
      expect(el<HTMLInputElement>('#consumer-name')!.value).toBe('consumidor-2');
      await startConsumer('pedidos', 'MANUAL');

      expect(consumers()).toHaveLength(2);
      expect(sources).toHaveLength(1);
      expect(startApi).toHaveBeenNthCalledWith(1, 's1', 'consumidor-1', 'pedidos', 'MANUAL');
      expect(startApi).toHaveBeenNthCalledWith(2, 's1', 'consumidor-2', 'pedidos', 'MANUAL');
    });

    it('rejects a name already used by an active consumer', async () => {
      await startConsumer('pedidos', 'MANUAL', 'repetido');
      await setForm('pedidos', 'MANUAL', 'repetido');

      expect(el('#consumer-name-error')?.textContent).toContain('Ya hay un consumidor activo con ese nombre.');
      submitButton().click();
      await settle();
      expect(startApi).toHaveBeenCalledTimes(1);
    });

    it('shows the error and keeps the form when the backend rejects the start', async () => {
      startApi.mockReturnValue(throwError(() => new ApiError('not-found', 'La cola «pedidos» no existe.')));

      await startConsumer('pedidos', 'MANUAL', 'c1');

      expect(alerts()).toContain('La cola «pedidos» no existe.');
      expect(consumers()).toHaveLength(0);
      expect(el<HTMLInputElement>('#consumer-name')!.value).toBe('c1');
      expect(reportFailure).toHaveBeenCalled();
    });

    it('disables the start button while starting and ignores repeats (CA-11.5)', async () => {
      const response = new Subject<ConsumerInfo>();
      startApi.mockReturnValue(response);

      await startConsumer('pedidos', 'MANUAL', 'c1');
      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await settle();
      expect(startApi).toHaveBeenCalledTimes(1);

      response.next({ id: 'id-c1', name: 'c1', queue: 'pedidos', ackMode: 'MANUAL' });
      await settle();
      expect(submitButton().disabled).toBe(false);
    });
  });

  describe('received messages', () => {
    beforeEach(async () => {
      await startConsumer('pedidos', 'MANUAL', 'c1');
    });

    function receive(tag: number, body: string, extra: Record<string, unknown> = {}) {
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: tag,
        message: { body, exchange: 'ex', routingKey: 'rk', redelivered: false, ...extra },
      });
    }

    it('shows the messages in a log region as they arrive (CA-9.2)', async () => {
      expect(logOf('c1').getAttribute('role')).toBe('log');
      expect(messagesOf('c1')).toHaveLength(0);

      receive(1, 'hola');
      await settle();

      expect(messagesOf('c1')).toHaveLength(1);
      expect(messagesOf('c1')[0].textContent).toContain('hola');
      expect(messagesOf('c1')[0].textContent).toContain('ex');
      expect(messagesOf('c1')[0].textContent).toContain('rk');
    });

    it('shows the newest message first', async () => {
      receive(1, 'primero');
      receive(2, 'segundo');
      await settle();

      expect(messagesOf('c1').map((m) => m.querySelector('.body')?.textContent)).toEqual(['segundo', 'primero']);
    });

    it('marks a redelivered message and names the default exchange', async () => {
      receive(1, 'otra vez', { redelivered: true, exchange: '', routingKey: 'pedidos' });
      await settle();

      expect(messagesOf('c1')[0].textContent).toContain('Reentregado');
      expect(messagesOf('c1')[0].textContent).toContain('(exchange por defecto)');
    });

    it('shows the origin and reason of a dead-letter queue message', async () => {
      receive(1, 'x', { deathInfo: { originQueue: 'orig', reason: 'rejected' } });
      await settle();

      expect(messagesOf('c1')[0].textContent).toContain('Cola de origen');
      expect(messagesOf('c1')[0].textContent).toContain('orig');
      expect(messagesOf('c1')[0].textContent).toContain('rejected');
    });

    it('shows a body with HTML as literal text, never as markup (RNF-5)', async () => {
      const body = '<img src=x onerror="window.hacked=1"><script>window.hacked=1</script>';
      receive(1, body);
      await settle();

      expect(messagesOf('c1')[0].querySelector('.body')?.textContent).toBe(body);
      expect(page.querySelector('.log img, .log script')).toBeNull();
      expect((window as unknown as Record<string, unknown>)['hacked']).toBeUndefined();
    });

    it('keeps the last 100 messages on screen and says so', async () => {
      for (let tag = 1; tag <= 105; tag++) {
        receive(tag, `m${tag}`);
      }
      await settle();

      expect(messagesOf('c1')).toHaveLength(100);
      expect(messagesOf('c1')[0].querySelector('.body')?.textContent).toBe('m105');
      expect(page.textContent).toContain('Se muestran los últimos 100 mensajes');
    });

    it('says an automatic consumer confirmed the message when receiving it', async () => {
      await startConsumer('pedidos', 'AUTOMATIC', 'auto');
      last().emit('message-received', {
        consumerId: 'id-auto',
        deliveryTag: 1,
        message: { body: 'x', exchange: '', routingKey: 'pedidos', redelivered: false },
      });
      await settle();

      expect(messagesOf('auto')[0].textContent).toContain('Confirmado automáticamente al recibirlo');
      expect(messagesOf('auto')[0].querySelector('.actions')).toBeNull();
    });

    it('keeps each consumer’s messages apart', async () => {
      await startConsumer('pedidos', 'MANUAL', 'c2');
      receive(1, 'para c1');
      last().emit('message-received', {
        consumerId: 'id-c2',
        deliveryTag: 1,
        message: { body: 'para c2', exchange: '', routingKey: 'q', redelivered: false },
      });
      await settle();

      expect(messagesOf('c1').map((m) => m.querySelector('.body')?.textContent)).toEqual(['para c1']);
      expect(messagesOf('c2').map((m) => m.querySelector('.body')?.textContent)).toEqual(['para c2']);
    });

    it('keeps the consumers and their messages when the user goes to another area and comes back (CL-24)', async () => {
      receive(1, 'sigue aquí');
      await settle();

      fixture.destroy();
      fixture = TestBed.createComponent(ConsumersPage);
      page = fixture.nativeElement as HTMLElement;
      await settle();

      expect(consumers()).toHaveLength(1);
      expect(messagesOf('c1')[0].textContent).toContain('sigue aquí');
      expect(sources[0].closed).toBe(false);
    });
  });

  describe('stopping', () => {
    beforeEach(async () => {
      await startConsumer('pedidos', 'MANUAL', 'c1');
    });

    it('stops the consumer and shows it as stopped, keeping its messages', async () => {
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 1,
        message: { body: 'queda', exchange: '', routingKey: 'q', redelivered: false },
      });
      await settle();

      buttonIn(consumers()[0], 'Detener').click();
      await settle();

      expect(stopApi).toHaveBeenCalledWith('s1', 'id-c1');
      expect(consumers()[0].textContent).toContain('Detenido');
      expect(consumers()[0].textContent).toContain('El consumidor «c1» se ha detenido.');
      expect(buttonIn(consumers()[0], 'Detener')).toBeUndefined();
      expect(messagesOf('c1')[0].textContent).toContain('queda');
    });

    it('shows the error when stopping fails', async () => {
      stopApi.mockReturnValue(throwError(() => new ApiError('not-found', 'El consumidor no existe en esta sesión.')));

      buttonIn(consumers()[0], 'Detener').click();
      await settle();

      expect(alerts()).toContain('El consumidor no existe en esta sesión.');
    });

    it('lets a stopped consumer’s name be used again', async () => {
      buttonIn(consumers()[0], 'Detener').click();
      await settle();

      await setForm('pedidos', 'MANUAL', 'c1');

      expect(el('#consumer-name-error')?.textContent?.trim()).toBe('');
    });
  });

  describe('consumer ends because of the queue (CA-9.9)', () => {
    it('tells the user the queue was deleted', async () => {
      await startConsumer('pedidos', 'MANUAL', 'c1');

      last().emit('consumer-stopped', {
        consumerId: 'id-c1',
        reason: 'QUEUE_DELETED',
        message: 'La cola «pedidos» se ha eliminado: el consumidor «c1» ha terminado.',
      });
      await settle();

      expect(consumers()[0].textContent).toContain('Detenido');
      expect(el('.stop-message')?.textContent).toContain('La cola «pedidos» se ha eliminado');
      expect(el('.stop-message')?.getAttribute('role')).toBe('status');
    });
  });

  describe('stream failure and reconnection', () => {
    beforeEach(async () => {
      await startConsumer('pedidos', 'MANUAL', 'c1');
    });

    it('stops the consumers with a notice in Spanish and offers to reconnect', async () => {
      last().fail();
      await settle();

      expect(el('.stream-error')?.getAttribute('role')).toBe('alert');
      expect(el('.stream-error')?.textContent).toContain('Se ha perdido la comunicación con el servidor');
      expect(consumers()[0].textContent).toContain('Detenido');
      expect(buttonIn(el('.stream-error')!, 'Reconectar')).toBeDefined();
    });

    it('does not reconnect by itself', async () => {
      last().fail();
      await settle();

      expect(sources).toHaveLength(1);
      expect(sources[0].closed).toBe(true);
    });

    it('opens a brand new session when pressing Reconnect and removes the notice', async () => {
      last().fail();
      await settle();

      buttonIn(el('.stream-error')!, 'Reconectar').click();
      await settle();
      expect(sources).toHaveLength(2);
      last().emit('session-started', { sessionId: 's2' });
      await settle();

      expect(el('.stream-error')).toBeNull();
      await startConsumer('pedidos', 'MANUAL', 'nuevo');
      expect(startApi).toHaveBeenLastCalledWith('s2', 'nuevo', 'pedidos', 'MANUAL');
    });

    it('disables the Reconnect button while reconnecting', async () => {
      last().fail();
      await settle();

      buttonIn(el('.stream-error')!, 'Reconectar').click();
      await settle();

      expect(buttonIn(el('.stream-error') ?? page, 'Reconectando…')?.disabled ?? true).toBe(true);
    });
  });

  describe('queue list', () => {
    it('shows an error when the queues cannot be loaded and can reload them', async () => {
      listQueues.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));

      buttonIn(el('form')!, 'Actualizar las colas').click();
      await settle();

      expect(page.textContent).toContain('RabbitMQ no está disponible.');
      expect(reportFailure).toHaveBeenCalled();
    });
  });

  describe('actions on a message (manual mode)', () => {
    beforeEach(async () => {
      await startConsumer('pedidos', 'MANUAL', 'c1');
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 7,
        message: { body: 'decide tú', exchange: 'ex', routingKey: 'rk', redelivered: false },
      });
      await settle();
    });

    const actions = () => messagesOf('c1')[0].querySelector('.actions')!;
    const press = async (label: string) => {
      buttonIn(actions(), label).click();
      await settle();
    };
    const outcome = () => messagesOf('c1')[0].querySelector('.outcome')?.textContent?.trim();

    it('offers the three actions as native buttons (CA-9.4)', () => {
      const buttons = Array.from(actions().querySelectorAll('button'));

      expect(buttons.map((b) => b.textContent?.trim())).toEqual([
        'Confirmar',
        'Rechazar y reencolar',
        'Rechazar sin reencolar',
      ]);
      buttons.forEach((b) => {
        expect(b.type).toBe('button');
        expect(b.disabled).toBe(false);
      });
      expect(actions().getAttribute('role')).toBe('group');
    });

    it('confirms the message and says it left the queue (CA-9.5)', async () => {
      await press('Confirmar');

      expect(resolveApi).toHaveBeenCalledWith('s1', 'id-c1', 7, 'ACK');
      expect(outcome()).toBe('Confirmado: el mensaje se ha eliminado de la cola.');
      expect(messagesOf('c1')[0].querySelector('.actions')).toBeNull();
    });

    it('rejects and requeues it, saying it will come back as redelivered (CA-9.6)', async () => {
      resolveApi.mockReturnValue(of('REQUEUED'));

      await press('Rechazar y reencolar');

      expect(resolveApi).toHaveBeenCalledWith('s1', 'id-c1', 7, 'REQUEUE');
      expect(outcome()).toContain('ha vuelto a la cola');
      expect(outcome()).toContain('reentregado');
    });

    it('rejects without requeueing and says it went to the dead-letter queue (CA-9.7)', async () => {
      resolveApi.mockReturnValue(of('DEAD_LETTERED'));

      await press('Rechazar sin reencolar');

      expect(resolveApi).toHaveBeenCalledWith('s1', 'id-c1', 7, 'REJECT');
      expect(outcome()).toContain('enviado a la dead-letter queue');
    });

    it('tells the message was discarded when the queue has no dead-letter queue (CA-9.7)', async () => {
      resolveApi.mockReturnValue(of('DISCARDED'));

      await press('Rechazar sin reencolar');

      expect(outcome()).toContain('El mensaje se ha descartado');
      expect(messagesOf('c1')[0].querySelector('.outcome.discarded')).not.toBeNull();
    });

    it('disables the three buttons as soon as one is pressed and blocks a second action (CA-9.10)', async () => {
      const response = new Subject<ActionOutcome>();
      resolveApi.mockReturnValue(response);

      await press('Confirmar');

      Array.from(actions().querySelectorAll('button')).forEach((b) => expect(b.disabled).toBe(true));
      buttonIn(actions(), 'Rechazar sin reencolar').click();
      await settle();
      expect(resolveApi).toHaveBeenCalledTimes(1);

      response.next('CONFIRMED');
      await settle();
      expect(outcome()).toContain('Confirmado');
    });

    it('shows the error for a double resolution and takes the actions away (CA-9.10)', async () => {
      resolveApi.mockReturnValue(throwError(() => new ApiError('conflict', 'Este mensaje ya se ha resuelto.')));

      await press('Confirmar');

      expect(messagesOf('c1')[0].querySelector('.error')?.textContent).toContain('Este mensaje ya se ha resuelto.');
      expect(messagesOf('c1')[0].querySelector('.actions')).toBeNull();
    });

    it('keeps the actions available, with the error, when the resolution fails for another reason', async () => {
      resolveApi.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));

      await press('Confirmar');

      expect(messagesOf('c1')[0].querySelector('.error')?.textContent).toContain('RabbitMQ no está disponible.');
      expect(buttonIn(actions(), 'Confirmar').disabled).toBe(false);
      expect(reportFailure).toHaveBeenCalled();
    });

    it('offers actions only on the message that is pending, not on the already resolved ones', async () => {
      await press('Confirmar');
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 8,
        message: { body: 'el siguiente', exchange: 'ex', routingKey: 'rk', redelivered: false },
      });
      await settle();

      const [newest, older] = messagesOf('c1');
      expect(newest.querySelector('.actions')).not.toBeNull();
      expect(older.querySelector('.actions')).toBeNull();
    });

    it('does not offer actions once the consumer has stopped', async () => {
      last().emit('consumer-stopped', { consumerId: 'id-c1', reason: 'QUEUE_DELETED', message: 'La cola se eliminó.' });
      await settle();

      expect(messagesOf('c1')[0].querySelector('.actions')).toBeNull();
      expect(messagesOf('c1')[0].textContent).toContain('ha vuelto a la cola');
    });
  });
});
