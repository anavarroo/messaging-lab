import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/http/api-error';
import { FlowMessage } from '../../../shared/flow/flow-model';
import { FakeEventSource } from '../../../testing/fake-event-source';
import { EVENT_SOURCE_FACTORY } from '../consumers/event-source-factory';
import { RabbitMqFlowSource } from './rabbitmq-flow-source';

describe('RabbitMqFlowSource', () => {
  let source: RabbitMqFlowSource;
  let http: HttpTestingController;
  let streams: FakeEventSource[];

  beforeEach(() => {
    streams = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        RabbitMqFlowSource,
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            const fake = new FakeEventSource(url);
            streams.push(fake);
            return fake;
          },
        },
      ],
    });
    source = TestBed.inject(RabbitMqFlowSource);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('recent events', () => {
    it('asks the backend for the latest 200', () => {
      let result: unknown;
      source.recent().subscribe((events) => (result = events));

      const request = http.expectOne('/api/rabbitmq/flow/events?limit=200');
      expect(request.request.method).toBe('GET');
      request.flush([{ id: 1 }]);
      expect(result).toEqual([{ id: 1 }]);
    });

    it('fails with a message in Spanish when the backend is down', () => {
      let error: ApiError | undefined;
      source.recent().subscribe({ error: (e) => (error = e) });

      http.expectOne('/api/rabbitmq/flow/events?limit=200').error(new ProgressEvent('error'), { status: 0 });

      expect(error?.message).toBe('El backend no está disponible.');
    });
  });

  describe('real-time stream', () => {
    it('does not connect until someone subscribes', () => {
      source.stream();

      expect(streams).toHaveLength(0);
    });

    it('opens the stream of the backend when subscribed', () => {
      source.stream().subscribe();

      expect(streams.map((s) => s.url)).toEqual(['/api/rabbitmq/flow/stream']);
    });

    it('turns each kind of server event into a message of the visualizer', () => {
      const messages: FlowMessage[] = [];
      source.stream().subscribe((message) => messages.push(message));

      streams[0].emit('status', { state: 'ACTIVE' });
      streams[0].emit('topology', { nodes: [], edges: [] });
      streams[0].emit('tick', { time: 't', published: {}, delivered: {}, ready: {}, omitted: 0 });
      streams[0].emit('flow-event', { id: 7 });

      expect(messages.map((message) => message.kind)).toEqual(['status', 'topology', 'tick', 'event']);
      expect(messages[3]).toEqual({ kind: 'event', event: { id: 7 } });
    });

    it('ignores a message it cannot read and keeps going', () => {
      const messages: FlowMessage[] = [];
      source.stream().subscribe((message) => messages.push(message));

      streams[0].emitRaw('flow-event', '{no es json');
      streams[0].emit('flow-event', { id: 2 });

      expect(messages).toHaveLength(1);
    });

    it('ends with an error when the connection is cut, so whoever uses it reconnects', () => {
      let error: Error | undefined;
      source.stream().subscribe({ error: (e) => (error = e) });

      streams[0].fail();

      expect(error?.message).toBe('Se ha cortado el flujo de datos.');
      expect(streams[0].closed).toBe(true);
    });

    it('closes the connection when the subscription is cancelled', () => {
      const subscription = source.stream().subscribe();

      subscription.unsubscribe();

      expect(streams[0].closed).toBe(true);
    });

    it('opens a new connection for each subscription', () => {
      source.stream().subscribe().unsubscribe();
      source.stream().subscribe();

      expect(streams).toHaveLength(2);
      expect(streams[1].closed).toBe(false);
    });
  });
});
