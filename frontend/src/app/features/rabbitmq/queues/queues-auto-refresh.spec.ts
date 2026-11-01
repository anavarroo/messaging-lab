import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { MESSAGES_REFRESH_MS, QueueMessagesPanel } from './queue-messages-panel';
import { Queue, QueueInspection, QueuesApi } from './queues-api';
import { QUEUES_REFRESH_MS, QueuesPage } from './queues-page';

const queue = (ready: number): Queue => ({ name: 'pedidos', deadLetterQueue: null, ready, unacknowledged: 0, total: ready });
const inspection = (...bodies: string[]): QueueInspection => ({
  messages: bodies.map((body) => ({ body, exchange: 'ex', routingKey: 'rk', redelivered: false })),
  total: bodies.length,
  truncated: false,
});

describe('automatic refresh of the queues area', () => {
  let list: ReturnType<typeof vi.fn<() => Observable<Queue[]>>>;
  let inspect: ReturnType<typeof vi.fn<(name: string) => Observable<QueueInspection>>>;
  let fixture: ComponentFixture<QueuesPage>;
  let page: HTMLElement;

  beforeEach(async () => {
    vi.useFakeTimers();
    list = vi.fn(() => of([queue(1)]));
    inspect = vi.fn(() => of(inspection('uno')));
    await TestBed.configureTestingModule({
      imports: [QueuesPage],
      providers: [
        { provide: QueuesApi, useValue: { list, inspect } },
        { provide: RabbitMqConnection, useValue: { reportFailure: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QueuesPage);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  async function elapse(ms: number) {
    await vi.advanceTimersByTimeAsync(ms);
    fixture.detectChanges();
  }
  const ready = () => page.querySelector('tbody tr td:nth-child(3)')?.textContent?.trim();

  it('updates the counters by itself, without pressing anything', async () => {
    expect(ready()).toBe('1');

    list.mockReturnValue(of([queue(250)]));
    await elapse(QUEUES_REFRESH_MS);

    expect(ready()).toBe('250');
    list.mockReturnValue(of([queue(400)]));
    await elapse(QUEUES_REFRESH_MS);
    expect(ready()).toBe('400');
  });

  it('refreshes silently: no loading message and the list stays if a refresh fails', async () => {
    list.mockReturnValue(throwError(() => new ApiError('broker-unavailable', 'RabbitMQ no está disponible.')));
    await elapse(QUEUES_REFRESH_MS);

    expect(page.querySelector('.loading')).toBeNull();
    expect(ready()).toBe('1');
    expect(page.querySelector('.error')?.textContent).toContain('RabbitMQ no está disponible.');
  });

  it('stops asking when the page is destroyed', async () => {
    fixture.destroy();
    list.mockClear();

    await vi.advanceTimersByTimeAsync(QUEUES_REFRESH_MS * 5);

    expect(list).not.toHaveBeenCalled();
  });

  describe('messages of an inspected queue', () => {
    const open = async () => {
      page.querySelector<HTMLButtonElement>('button[aria-label^="Inspeccionar"]')!.click();
      await elapse(1);
    };
    const bodies = () => page.textContent ?? '';

    it('are read again by themselves', async () => {
      await open();
      expect(bodies()).toContain('uno');

      inspect.mockReturnValue(of(inspection('uno', 'dos')));
      await elapse(MESSAGES_REFRESH_MS);

      expect(bodies()).toContain('dos');
    });

    it('stop updating when the user turns the option off, and resume when turned on', async () => {
      await open();
      const toggle = page.querySelector<HTMLInputElement>('.auto-refresh input')!;
      expect(toggle.checked).toBe(true);

      toggle.click();
      fixture.detectChanges();
      inspect.mockClear();
      await elapse(MESSAGES_REFRESH_MS * 3);
      expect(inspect).not.toHaveBeenCalled();

      toggle.click();
      fixture.detectChanges();
      await elapse(MESSAGES_REFRESH_MS);
      expect(inspect).toHaveBeenCalledTimes(1);
    });

    it('do not blink: the panel keeps showing the messages while it rereads', async () => {
      await open();
      inspect.mockReturnValue(new Observable<QueueInspection>());
      await elapse(MESSAGES_REFRESH_MS);

      expect(page.querySelector('.loading')).toBeNull();
      expect(bodies()).toContain('uno');
    });
  });
});
