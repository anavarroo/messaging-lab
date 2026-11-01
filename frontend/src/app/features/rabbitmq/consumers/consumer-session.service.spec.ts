import { TestBed } from '@angular/core/testing';
import { Observable, Subject, firstValueFrom, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { ActionOutcome, ConsumerInfo, ConsumersApi } from './consumers-api';
import { ConsumerSessionService, MAX_MESSAGES_PER_CONSUMER } from './consumer-session.service';
import { FakeEventSource } from '../../../testing/fake-event-source';
import { EVENT_SOURCE_FACTORY } from './event-source-factory';

describe('ConsumerSessionService', () => {
  const consumer = (id: string, name = id, ackMode: 'MANUAL' | 'AUTOMATIC' = 'MANUAL'): ConsumerInfo => ({
    id,
    name,
    queue: 'pedidos',
    ackMode,
  });
  const message = (body: string) => ({ body, exchange: 'ex', routingKey: 'rk', redelivered: false });

  let service: ConsumerSessionService;
  let sources: FakeEventSource[];
  let startApi: ReturnType<typeof vi.fn<(s: string, n: string, q: string, m: 'AUTOMATIC' | 'MANUAL') => Observable<ConsumerInfo>>>;
  let stopApi: ReturnType<typeof vi.fn<(s: string, c: string) => Observable<void>>>;
  let resolveApi: ReturnType<typeof vi.fn<(s: string, c: string, t: number, a: string) => Observable<ActionOutcome>>>;
  let reportFailure: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sources = [];
    startApi = vi.fn((_s, name, _q, mode) => of(consumer(`id-${name}`, name, mode)));
    stopApi = vi.fn(() => of(undefined));
    resolveApi = vi.fn(() => of('CONFIRMED' as ActionOutcome));
    reportFailure = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        ConsumerSessionService,
        { provide: ConsumersApi, useValue: { start: startApi, stop: stopApi, resolve: resolveApi } },
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
    });
    service = TestBed.inject(ConsumerSessionService);
  });

  /** Inicia un consumidor respondiendo `session-started` en cuanto se abre el flujo. */
  async function start(name: string, mode: 'MANUAL' | 'AUTOMATIC' = 'MANUAL', sessionId = 's1') {
    const pending = firstValueFrom(service.start(name, 'pedidos', mode));
    if (sources.length > 0 && sources[sources.length - 1].closed === false) {
      sources[sources.length - 1].emit('session-started', { sessionId });
    }
    return pending;
  }

  const last = () => sources[sources.length - 1];
  const view = (id: string) => service.consumers().find((c) => c.info.id === id)!;

  describe('opening the stream', () => {
    it('does not open the stream until the first consumer is created (CA-9.1)', () => {
      expect(sources).toHaveLength(0);
      expect(service.streamState()).toBe('idle');
    });

    it('opens the stream of the session events when the first consumer is created', async () => {
      const pending = firstValueFrom(service.start('c1', 'pedidos', 'MANUAL'));
      await Promise.resolve();

      expect(sources).toHaveLength(1);
      expect(sources[0].url).toBe('/api/rabbitmq/session/events');
      expect(service.streamState()).toBe('connecting');
      sources[0].emit('session-started', { sessionId: 's1' });
      await pending;
      expect(service.streamState()).toBe('open');
    });

    it('creates the consumer in the session announced by the stream', async () => {
      await start('c1');

      expect(startApi).toHaveBeenCalledWith('s1', 'c1', 'pedidos', 'MANUAL');
    });

    it('reuses the same stream for the next consumers: one stream per tab', async () => {
      await start('c1');
      await start('c2');
      await start('c3', 'AUTOMATIC');

      expect(sources).toHaveLength(1);
      expect(startApi).toHaveBeenCalledTimes(3);
    });

    it('does not open the stream if nobody subscribes to the start', () => {
      service.start('c1', 'pedidos', 'MANUAL');

      expect(sources).toHaveLength(0);
    });
  });

  describe('consumers', () => {
    it('lists the consumer as active as soon as it is created', async () => {
      await start('c1');

      expect(service.consumers().map((c) => [c.info.name, c.active])).toEqual([['c1', true]]);
    });

    it('does not duplicate a consumer announced both by the response and by the stream', async () => {
      await start('c1');
      last().emit('consumer-started', { consumer: consumer('id-c1', 'c1') });

      expect(service.consumers()).toHaveLength(1);
    });

    it('lists several consumers on the same queue (CA-9.11)', async () => {
      await start('c1');
      await start('c2');

      expect(service.consumers().map((c) => c.info.name)).toEqual(['c1', 'c2']);
      expect(service.activeConsumers()).toHaveLength(2);
    });

    it('shows a consumer announced by the stream even if the response has not arrived', async () => {
      const response = new Subject<ConsumerInfo>();
      startApi.mockReturnValue(response);
      const pending = firstValueFrom(service.start('c1', 'pedidos', 'MANUAL'));
      await Promise.resolve();
      last().emit('session-started', { sessionId: 's1' });
      await Promise.resolve();

      last().emit('consumer-started', { consumer: consumer('id-c1', 'c1') });
      expect(service.consumers()).toHaveLength(1);

      response.next(consumer('id-c1', 'c1'));
      await pending;
      expect(service.consumers()).toHaveLength(1);
    });

    it('stops a consumer calling the backend and marks it stopped, keeping its messages', async () => {
      await start('c1');
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('x') });

      await firstValueFrom(service.stop('id-c1'));

      expect(stopApi).toHaveBeenCalledWith('s1', 'id-c1');
      expect(view('id-c1').active).toBe(false);
      expect(view('id-c1').stopReason).toBe('USER');
      expect(view('id-c1').stopMessage).toBe('El consumidor «c1» se ha detenido.');
      expect(view('id-c1').messages).toHaveLength(1);
      expect(service.activeConsumers()).toHaveLength(0);
    });

    it('marks a consumer stopped with the reason and message the backend sends', async () => {
      await start('c1');

      last().emit('consumer-stopped', {
        consumerId: 'id-c1',
        reason: 'QUEUE_DELETED',
        message: 'La cola «pedidos» se ha eliminado: el consumidor «c1» ha terminado.',
      });

      expect(view('id-c1').active).toBe(false);
      expect(view('id-c1').stopReason).toBe('QUEUE_DELETED');
      expect(view('id-c1').stopMessage).toContain('se ha eliminado');
    });

    it('keeps the first reason when a stop is announced twice (response and stream)', async () => {
      await start('c1');
      last().emit('consumer-stopped', { consumerId: 'id-c1', reason: 'USER', message: 'detenido' });

      last().emit('consumer-stopped', { consumerId: 'id-c1', reason: 'CONNECTION_LOST', message: 'otra' });

      expect(view('id-c1').stopReason).toBe('USER');
    });

    it('reports a failure to start to the connection and rethrows it', async () => {
      const failure = new ApiError('conflict', 'Ya hay un consumidor con el nombre «c1» en esta sesión.');
      startApi.mockReturnValue(throwError(() => failure));

      await expect(start('c1')).rejects.toBe(failure);

      expect(reportFailure).toHaveBeenCalledWith(failure);
      expect(service.consumers()).toHaveLength(0);
    });
  });

  describe('messages', () => {
    beforeEach(async () => {
      await start('c1');
      await start('c2', 'AUTOMATIC');
    });

    it('shows the received messages with their data, in order (CA-9.2)', () => {
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('uno') });
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 2,
        message: { ...message('dos'), redelivered: true, exchange: '', routingKey: 'pedidos' },
      });

      const messages = view('id-c1').messages;
      expect(messages.map((m) => m.body)).toEqual(['uno', 'dos']);
      expect(messages[0]).toMatchObject({ deliveryTag: 1, exchange: 'ex', routingKey: 'rk', redelivered: false });
      expect(messages[1]).toMatchObject({ exchange: '', routingKey: 'pedidos', redelivered: true });
    });

    it('keeps the origin of a dead-letter queue message', () => {
      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 1,
        message: { ...message('x'), deathInfo: { originQueue: 'orig', reason: 'rejected' } },
      });

      expect(view('id-c1').messages[0].deathInfo).toEqual({ originQueue: 'orig', reason: 'rejected' });
    });

    it('gives each consumer its own messages', () => {
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('para c1') });
      last().emit('message-received', { consumerId: 'id-c2', deliveryTag: 1, message: message('para c2') });

      expect(view('id-c1').messages.map((m) => m.body)).toEqual(['para c1']);
      expect(view('id-c2').messages.map((m) => m.body)).toEqual(['para c2']);
    });

    it('leaves the messages of a manual consumer pending and marks those of an automatic one as confirmed', () => {
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('a') });
      last().emit('message-received', { consumerId: 'id-c2', deliveryTag: 1, message: message('b') });

      expect(view('id-c1').messages[0].state).toBe('pending');
      expect(view('id-c2').messages[0].state).toBe('automatic');
    });

    it('keeps only the last 100 messages of each consumer', () => {
      for (let tag = 1; tag <= MAX_MESSAGES_PER_CONSUMER + 25; tag++) {
        last().emit('message-received', { consumerId: 'id-c2', deliveryTag: tag, message: message(`m${tag}`) });
      }

      const messages = view('id-c2').messages;
      expect(messages).toHaveLength(MAX_MESSAGES_PER_CONSUMER);
      expect(messages[0].body).toBe('m26');
      expect(messages[messages.length - 1].body).toBe('m125');
    });

    it('ignores a message for an unknown consumer and an event that is not valid JSON', () => {
      last().emit('message-received', { consumerId: 'desconocido', deliveryTag: 1, message: message('x') });
      last().emitRaw('message-received', '{no es json');

      expect(view('id-c1').messages).toHaveLength(0);
    });
  });

  describe('closing', () => {
    it('closes the stream when the service is destroyed (CA-9.13)', async () => {
      await start('c1');

      TestBed.resetTestingModule();

      expect(sources[0].closed).toBe(true);
    });

    it('stopAll closes the stream and marks every active consumer stopped, keeping the messages', async () => {
      await start('c1');
      await start('c2');
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('x') });

      service.stopAll();

      expect(sources[0].closed).toBe(true);
      expect(service.activeConsumers()).toHaveLength(0);
      expect(view('id-c1').messages).toHaveLength(1);
      expect(view('id-c1').stopReason).toBe('SESSION_CLOSED');
      expect(service.streamState()).toBe('idle');
    });

    it('opens a new stream when a consumer is created after stopAll', async () => {
      await start('c1');
      service.stopAll();

      await start('c3', 'MANUAL', 's2');

      expect(sources).toHaveLength(2);
      expect(startApi).toHaveBeenLastCalledWith('s2', 'c3', 'pedidos', 'MANUAL');
    });
  });

  describe('resolving messages', () => {
    beforeEach(async () => {
      await start('c1');
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('uno') });
    });

    const state = () => view('id-c1').messages[0];

    it.each([
      ['ACK', 'CONFIRMED'],
      ['REQUEUE', 'REQUEUED'],
      ['REJECT', 'DEAD_LETTERED'],
      ['REJECT', 'DISCARDED'],
    ] as const)('%s ends as %s and the message is resolved', async (action, outcome) => {
      resolveApi.mockReturnValue(of(outcome));

      const result = await firstValueFrom(service.resolve('id-c1', 1, action));

      expect(result).toBe(outcome);
      expect(resolveApi).toHaveBeenCalledWith('s1', 'id-c1', 1, action);
      expect(state()).toMatchObject({ state: 'resolved', outcome });
    });

    it('marks the message as resolving while the action is in progress and refuses another one (CA-9.10)', async () => {
      const response = new Subject<ActionOutcome>();
      resolveApi.mockReturnValue(response);

      const pending = firstValueFrom(service.resolve('id-c1', 1, 'ACK'));
      expect(state().state).toBe('resolving');

      await expect(firstValueFrom(service.resolve('id-c1', 1, 'REJECT'))).rejects.toBeInstanceOf(ApiError);
      expect(resolveApi).toHaveBeenCalledTimes(1);

      response.next('CONFIRMED');
      await pending;
      expect(state().state).toBe('resolved');
    });

    it('refuses to resolve a message that is already resolved', async () => {
      await firstValueFrom(service.resolve('id-c1', 1, 'ACK'));

      await expect(firstValueFrom(service.resolve('id-c1', 1, 'ACK'))).rejects.toBeInstanceOf(ApiError);
      expect(resolveApi).toHaveBeenCalledTimes(1);
    });

    it('refuses to resolve an unknown message', async () => {
      await expect(firstValueFrom(service.resolve('id-c1', 99, 'ACK'))).rejects.toBeInstanceOf(ApiError);
      expect(resolveApi).not.toHaveBeenCalled();
    });

    it('treats a conflict from the backend as already resolved, with its message', async () => {
      const conflict = new ApiError('conflict', 'Este mensaje ya se ha resuelto.');
      resolveApi.mockReturnValue(throwError(() => conflict));

      await expect(firstValueFrom(service.resolve('id-c1', 1, 'ACK'))).rejects.toBe(conflict);

      expect(state()).toMatchObject({ state: 'resolved', error: 'Este mensaje ya se ha resuelto.' });
    });

    it('leaves the message pending, with the error, when another failure happens so it can be retried', async () => {
      const failure = new ApiError('broker-unavailable', 'RabbitMQ no está disponible.');
      resolveApi.mockReturnValue(throwError(() => failure));

      await expect(firstValueFrom(service.resolve('id-c1', 1, 'ACK'))).rejects.toBe(failure);

      expect(state()).toMatchObject({ state: 'pending', error: 'RabbitMQ no está disponible.' });
      expect(reportFailure).toHaveBeenCalledWith(failure);
      resolveApi.mockReturnValue(of('CONFIRMED'));
      await firstValueFrom(service.resolve('id-c1', 1, 'ACK'));
      expect(state()).toMatchObject({ state: 'resolved', outcome: 'CONFIRMED', error: undefined });
    });

    it('shows a requeued message again as a new redelivered one', async () => {
      await firstValueFrom(service.resolve('id-c1', 1, 'REQUEUE'));

      last().emit('message-received', {
        consumerId: 'id-c1',
        deliveryTag: 2,
        message: { ...message('uno'), redelivered: true },
      });

      expect(view('id-c1').messages.map((m) => [m.deliveryTag, m.state, m.redelivered])).toEqual([
        [1, 'resolved', false],
        [2, 'pending', true],
      ]);
    });
  });

  describe('stream failures', () => {
    it('stops every consumer with a notice and does not reconnect by itself when the stream fails', async () => {
      await start('c1');
      await start('c2');
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('x') });

      last().fail();

      expect(sources).toHaveLength(1);
      expect(sources[0].closed).toBe(true);
      expect(service.streamState()).toBe('broken');
      expect(service.streamError()).toContain('Se ha perdido la comunicación con el servidor');
      expect(service.activeConsumers()).toHaveLength(0);
      expect(service.consumers().every((c) => c.stopReason === 'CONNECTION_LOST')).toBe(true);
      expect(service.consumers()[0].stopMessage).toContain('volverán a la cola');
      expect(service.consumers()[0].messages).toHaveLength(1);
    });

    it('does not keep the failed stream alive to retry', async () => {
      await start('c1');
      last().fail();

      expect(last().onerror).toBeNull();
      expect(sources).toHaveLength(1);
    });

    it('fails the start with a notice when the stream cannot be opened at all', async () => {
      const pending = firstValueFrom(service.start('c1', 'pedidos', 'MANUAL'));
      await Promise.resolve();

      last().fail();

      await expect(pending).rejects.toBeInstanceOf(ApiError);
      expect(service.streamState()).toBe('broken');
      expect(service.streamError()).toContain('No se ha podido abrir la conexión');
      expect(startApi).not.toHaveBeenCalled();
    });

    it('refuses to stop or resolve through a lost session', async () => {
      await start('c1');
      last().emit('message-received', { consumerId: 'id-c1', deliveryTag: 1, message: message('x') });
      last().fail();

      await expect(firstValueFrom(service.stop('id-c1'))).rejects.toBeInstanceOf(ApiError);
      expect(stopApi).not.toHaveBeenCalled();
    });

    it('reconnect opens a brand new session and clears the notice', async () => {
      await start('c1');
      last().fail();
      expect(service.streamError()).not.toBeNull();

      const pending = firstValueFrom(service.reconnect());
      await Promise.resolve();
      expect(sources).toHaveLength(2);
      expect(service.streamError()).toBeNull();
      last().emit('session-started', { sessionId: 's2' });
      await pending;

      expect(service.streamState()).toBe('open');
      await firstValueFrom(service.start('c3', 'pedidos', 'MANUAL'));
      expect(startApi).toHaveBeenLastCalledWith('s2', 'c3', 'pedidos', 'MANUAL');
    });

    it('keeps the consumers of the lost session as stopped after reconnecting', async () => {
      await start('c1');
      last().fail();

      const pending = firstValueFrom(service.reconnect());
      await Promise.resolve();
      last().emit('session-started', { sessionId: 's2' });
      await pending;

      expect(service.consumers().map((c) => [c.info.name, c.active])).toEqual([['c1', false]]);
    });

    it('shows the notice again if the reconnection fails', async () => {
      await start('c1');
      last().fail();

      const pending = firstValueFrom(service.reconnect());
      await Promise.resolve();
      last().fail();

      await expect(pending).rejects.toBeInstanceOf(ApiError);
      expect(service.streamState()).toBe('broken');
      expect(service.streamError()).toContain('No se ha podido abrir la conexión');
    });
  });
});
