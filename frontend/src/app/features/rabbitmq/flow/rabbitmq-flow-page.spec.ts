import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FakeEventSource } from '../../../testing/fake-event-source';
import { EVENT_SOURCE_FACTORY } from '../consumers/event-source-factory';
import { RabbitMqFlowPage } from './rabbitmq-flow-page';

describe('RabbitMqFlowPage', () => {
  let stream: FakeEventSource;
  let http: HttpTestingController;
  let fixture: ComponentFixture<RabbitMqFlowPage>;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RabbitMqFlowPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => (stream = new FakeEventSource(url)) },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RabbitMqFlowPage);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/rabbitmq/flow/events?limit=200').flush([]);
    fixture.detectChanges();
  });

  it('explains what the user sees and the cost of the trace', () => {
    expect(root.querySelector('h3')?.textContent).toBe('Flujo en tiempo real');
    expect(root.querySelector('.intro')?.textContent).toContain('trazado');
    expect(root.querySelector('.intro')?.textContent).toContain('no para producción');
  });

  it('explains what the default exchange is and how the generator appears', () => {
    expect(root.textContent).toContain('exchange por defecto');
    expect(root.textContent).toContain('con su propio nombre');
  });

  it('connects to the backend stream through the shared visualizer', () => {
    expect(stream.url).toBe('/api/rabbitmq/flow/stream');
    expect(root.querySelector('app-flow-view')).not.toBeNull();
  });

  it('speaks RabbitMQ: exchanges, queues and routing keys', () => {
    stream.emit('status', { state: 'ACTIVE' });
    stream.emit('topology', {
      nodes: [
        { id: 'router:ventas', kind: 'ROUTER', label: 'ventas', detail: 'topic' },
        { id: 'destination:cocina', kind: 'DESTINATION', label: 'cocina' },
      ],
      edges: [],
    });
    stream.emit('flow-event', {
      id: 1, time: '2026-10-30T10:00:00Z', type: 'PUBLISH', router: 'router:ventas', destinations: ['destination:cocina'],
      key: 'k', sizeBytes: 1, redelivered: false, unrouted: false,
    });
    fixture.detectChanges();

    const captions = Array.from(root.querySelectorAll('g.node .caption')).map((c) => c.textContent);
    expect(captions).toEqual(expect.arrayContaining(['Exchange · topic', 'Cola']));
    expect(root.textContent).toContain('routing key');
  });

  it('shows how to enable the trace when the backend says it is off', () => {
    stream.emit('status', { state: 'DISABLED', hint: 'Ejecuta «rabbitmqctl trace_on»' });
    fixture.detectChanges();

    expect(root.querySelector('.error')?.textContent).toContain('rabbitmqctl trace_on');
  });
});
