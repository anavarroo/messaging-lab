import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { RabbitMqHealthApi, TracingStatus } from './rabbitmq-health-api';
import { RabbitMqStatus } from './rabbitmq-status';

describe('RabbitMqStatus', () => {
  let versions: Subject<string>[];
  let tracings: Subject<TracingStatus>[];
  let fixture: ComponentFixture<RabbitMqStatus>;
  let root: HTMLElement;

  beforeEach(async () => {
    versions = [];
    tracings = [];
    await TestBed.configureTestingModule({
      imports: [RabbitMqStatus],
      providers: [
        {
          provide: RabbitMqHealthApi,
          useValue: {
            version: () => {
              const s = new Subject<string>();
              versions.push(s);
              return s;
            },
            tracing: () => {
              const s = new Subject<TracingStatus>();
              tracings.push(s);
              return s;
            },
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(RabbitMqStatus);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const settle = () => fixture.detectChanges();
  const last = <T>(list: Subject<T>[]) => list[list.length - 1];
  const rows = () =>
    Array.from(root.querySelectorAll('dt')).map((dt) => [dt.textContent?.trim(), dt.nextElementSibling?.textContent?.replace(/\s+/g, ' ').trim()]);
  const button = () => root.querySelector('button') as HTMLButtonElement;
  const answer = (version: string | ApiError, tracing: TracingStatus | ApiError) => {
    version instanceof ApiError ? last(versions).error(version) : last(versions).next(version);
    tracing instanceof ApiError ? last(tracings).error(tracing) : last(tracings).next(tracing);
    settle();
  };

  it('checks both things as soon as it opens, saying so meanwhile', () => {
    expect(versions).toHaveLength(1);
    expect(tracings).toHaveLength(1);
    expect(rows()).toEqual([
      ['RabbitMQ', 'Comprobando…'],
      ['Trazado', 'Comprobando…'],
    ]);
    expect(button().disabled).toBe(true);
  });

  it('says RabbitMQ is up, with its version, and the trace is active', () => {
    answer('4.4.0', { state: 'ACTIVE' });

    expect(rows()).toEqual([
      ['RabbitMQ', 'Levantado (versión 4.4.0)'],
      ['Trazado', 'Activo (tracing: true)'],
    ]);
    expect(root.querySelector('.ok')).not.toBeNull();
    expect(root.textContent).toContain('puede mostrar los mensajes en tiempo real');
    expect(button().disabled).toBe(false);
  });

  it('says the trace is off and shows how to enable it', () => {
    answer('4.4.0', { state: 'DISABLED', hint: 'Ejecuta «rabbitmqctl trace_on»' });

    expect(rows()[0][1]).toContain('Levantado');
    expect(rows()[1]).toEqual(['Trazado', 'Desactivado (tracing: false)']);
    expect(root.querySelector('.warning')?.textContent).toContain('Ejecuta «rabbitmqctl trace_on»');
    expect(root.querySelector('.warning')?.getAttribute('role')).toBe('status');
  });

  it('says RabbitMQ is down with the reason and does not claim anything about the trace', () => {
    answer(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'), { state: 'UNAVAILABLE' });

    expect(rows()).toEqual([
      ['RabbitMQ', 'No disponible'],
      ['Trazado', 'No se puede comprobar sin RabbitMQ.'],
    ]);
    expect(root.querySelector('.error')?.textContent).toContain('RabbitMQ no está disponible.');
    expect(root.querySelector('.error')?.getAttribute('role')).toBe('alert');
  });

  it('explains that the trace cannot be checked when the backend answers that RabbitMQ is unreachable', () => {
    answer('4.4.0', { state: 'UNAVAILABLE' });

    expect(rows()[1][1]).toContain('No se puede comprobar: RabbitMQ no está disponible.');
  });

  it('shows the error when the trace check itself fails', () => {
    answer('4.4.0', new ApiError('backend-unavailable', 'El backend no está disponible.'));

    expect(rows()[1]).toEqual(['Trazado', 'No se ha podido comprobar.']);
    expect(root.querySelector('.error')?.textContent).toContain('El backend no está disponible.');
  });

  describe('checking again', () => {
    it('asks both questions again, and shows the new answer', () => {
      answer('4.4.0', { state: 'DISABLED', hint: 'x' });

      button().click();
      settle();
      expect(versions).toHaveLength(2);
      expect(tracings).toHaveLength(2);
      expect(rows()[1][1]).toBe('Comprobando…');

      answer('4.4.0', { state: 'ACTIVE' });
      expect(rows()[1][1]).toContain('Activo');
      expect(root.querySelector('.warning')).toBeNull();
    });

    it('recovers when RabbitMQ comes back', () => {
      answer(new ApiError('broker-unavailable', 'RabbitMQ no está disponible.'), { state: 'UNAVAILABLE' });

      button().click();
      answer('4.4.0', { state: 'ACTIVE' });

      expect(rows()[0][1]).toContain('Levantado');
      expect(root.querySelector('.error')).toBeNull();
    });

    it('ignores the button while a check is in progress', () => {
      button().click();

      expect(versions).toHaveLength(1);
    });
  });
});
