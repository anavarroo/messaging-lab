import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { EmitterFormValue, PreviewResult, TemplatePreset } from '../emitter-view';
import { EmitterForm } from './emitter-form';

const PRESETS: TemplatePreset[] = [
  {
    id: 'temperatura',
    name: 'Sensor de temperatura',
    description: 'Una temperatura que deriva poco a poco.',
    template: '{"t": {{deriva(20, 0.5)}}}',
    suggestedInterval: { mode: 'FIXED', fixedMs: 2000 },
    suggestedRoutingKey: 'lab.temperatura',
    onlyWhenChanged: true,
  },
  {
    id: 'alarma',
    name: 'Alarma',
    description: 'Alarmas esporádicas.',
    template: '{"alarma": {{booleano(0.1)}}}',
    suggestedInterval: { mode: 'RANGE', minMs: 500, maxMs: 3000 },
    suggestedRoutingKey: 'lab.alarma',
    onlyWhenChanged: false,
  },
];

@Component({
  imports: [EmitterForm],
  template: `
    <app-emitter-form
      [presets]="presets"
      [previewer]="previewer()"
      [saving]="saving()"
      [submitError]="submitError()"
      (submitted)="submissions.push($event)"
      (presetApplied)="applied.push($event.id)"
    >
      <p destination id="slot">destino</p>
    </app-emitter-form>
  `,
})
class Host {
  presets = PRESETS;
  saving = signal(false);
  submitError = signal<string | null>(null);
  previewer = signal<(template: string) => Observable<PreviewResult>>(() => of({ body: '{"t": 20.1}', bytes: 11 }));
  submissions: EmitterFormValue[] = [];
  applied: string[] = [];
  form = viewChild.required(EmitterForm);
}

describe('EmitterForm', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const el = <T extends HTMLElement>(selector: string) => root.querySelector<T>(selector)!;
  const type = (selector: string, value: string) => {
    const input = el<HTMLInputElement | HTMLTextAreaElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const select = (selector: string, value: string) => {
    const field = el<HTMLSelectElement>(selector);
    field.value = value;
    field.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  };
  const click = (label: string) => {
    const button = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)!;
    button.click();
    fixture.detectChanges();
  };
  const submit = () => {
    el<HTMLFormElement>('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  };
  const errors = () => Array.from(root.querySelectorAll('.field-error')).map((e) => e.textContent?.trim()).filter(Boolean);

  it('projects the destination into its slot', () => {
    expect(el('#slot').textContent).toBe('destino');
  });

  describe('frequency', () => {
    it('is fixed by default and shows the maximum rate', () => {
      expect(el<HTMLInputElement>('#emitter-fixed').value).toBe('1000');
      expect(root.querySelector('#emitter-min')).toBeNull();
      expect(el('.frequency-summary').textContent).toContain('Publicará cada 1 s: hasta 1 mensajes por segundo.');
    });

    it('submits a fixed frequency', () => {
      type('#emitter-name', 'robot 1');
      type('#emitter-template', '{"x": 1}');
      type('#emitter-fixed', '250');

      submit();

      expect(host.submissions).toEqual([
        {
          name: 'robot 1',
          interval: { mode: 'FIXED', fixedMs: 250 },
          template: '{"x": 1}',
          maxMessages: null,
          maxDurationSeconds: null,
          onlyWhenChanged: false,
        },
      ]);
    });

    it('submits a random range', () => {
      type('#emitter-name', 'robot 2');
      type('#emitter-template', '{"x": 1}');
      el<HTMLInputElement>('input[type=radio][value=RANGE]').click();
      fixture.detectChanges();
      expect(root.querySelector('#emitter-fixed')).toBeNull();
      type('#emitter-min', '200');
      type('#emitter-max', '800');

      expect(el('.frequency-summary').textContent).toContain('entre 200 ms y 800 ms: hasta 5 mensajes por segundo');
      submit();

      expect(host.submissions[0].interval).toEqual({ mode: 'RANGE', minMs: 200, maxMs: 800 });
    });

    it('rejects a missing, too short or inverted frequency without submitting', () => {
      type('#emitter-name', 'robot');
      type('#emitter-template', 'x');
      type('#emitter-fixed', '');
      submit();
      expect(errors()).toContain('Indica el valor fijo de la frecuencia, en milisegundos.');

      type('#emitter-fixed', '1');
      expect(errors()).toContain('Los intervalos deben estar entre 2 ms y 86400000 ms (un día).');

      el<HTMLInputElement>('input[type=radio][value=RANGE]').click();
      fixture.detectChanges();
      type('#emitter-min', '900');
      type('#emitter-max', '100');
      expect(errors().join(' ')).toContain('El mínimo del rango (900 ms) no puede ser mayor que el máximo (100 ms).');
      expect(host.submissions).toEqual([]);
    });
  });

  describe('validation', () => {
    it('asks for the name and the template only after trying to submit', () => {
      expect(errors()).toEqual([]);

      submit();

      expect(errors()).toEqual([
        'Escribe un nombre para el emisor.',
        'Escribe la plantilla del mensaje o elige una del catálogo.',
      ]);
      expect(el('#emitter-name').getAttribute('aria-invalid')).toBe('true');
      expect(host.submissions).toEqual([]);
    });

    it('rejects limits that are not positive whole numbers and accepts empty ones', () => {
      type('#emitter-name', 'a');
      type('#emitter-template', 'x');
      type('#emitter-max-messages', '0');
      type('#emitter-max-duration', '2.5');
      submit();
      expect(errors()).toEqual([
        'El número máximo de mensajes debe ser un número entero mayor que 0.',
        'La duración máxima debe ser un número entero mayor que 0.',
      ]);

      type('#emitter-max-messages', '10');
      type('#emitter-max-duration', '');
      submit();
      expect(host.submissions[0]).toMatchObject({ maxMessages: 10, maxDurationSeconds: null });
    });

    it('does not submit while saving', () => {
      type('#emitter-name', 'a');
      type('#emitter-template', 'x');
      host.saving.set(true);
      fixture.detectChanges();

      submit();

      expect(host.submissions).toEqual([]);
      expect(el<HTMLButtonElement>('button[type=submit]').disabled).toBe(true);
    });

    it('shows the error the server gave when creating', () => {
      host.submitError.set('Error en la plantilla (línea 1, columna 3): …');
      fixture.detectChanges();

      expect(el('.submit-error').textContent).toContain('Error en la plantilla (línea 1, columna 3)');
    });
  });

  describe('template catalog', () => {
    it('fills the form with the chosen template and tells who provides the destination', () => {
      select('#emitter-preset', 'alarma');
      expect(el('.preset-description').textContent).toContain('Alarmas esporádicas.');

      click('Usar plantilla');

      expect(el<HTMLTextAreaElement>('#emitter-template').value).toBe('{"alarma": {{booleano(0.1)}}}');
      expect(el<HTMLInputElement>('#emitter-name').value).toBe('Alarma');
      expect(el<HTMLInputElement>('#emitter-min').value).toBe('500');
      expect(el<HTMLInputElement>('#emitter-max').value).toBe('3000');
      expect(host.applied).toEqual(['alarma']);
    });

    it('applies a fixed interval and the "only when changed" option, keeping a name already typed', () => {
      type('#emitter-name', 'mi sensor');
      select('#emitter-preset', 'temperatura');
      click('Usar plantilla');

      expect(el<HTMLInputElement>('#emitter-fixed').value).toBe('2000');
      expect(el<HTMLInputElement>('input[type=checkbox]').checked).toBe(true);
      expect(el<HTMLInputElement>('#emitter-name').value).toBe('mi sensor');
      submit();
      expect(host.submissions[0]).toMatchObject({
        template: '{"t": {{deriva(20, 0.5)}}}',
        interval: { mode: 'FIXED', fixedMs: 2000 },
        onlyWhenChanged: true,
      });
    });

    it('keeps the button disabled until a template is chosen', () => {
      const button = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Usar plantilla')!;
      expect(button.disabled).toBe(true);
    });
  });

  describe('preview', () => {
    it('shows an example of the message and its size', () => {
      type('#emitter-template', '{"t": {{numero(1, 2)}}}');

      click('Vista previa');

      expect(el('.preview-body').textContent).toBe('{"t": 20.1}');
      expect(el('.preview-size').textContent).toContain('11 bytes');
    });

    it('rejects an invalid template showing the reason and its position', () => {
      host.previewer.set(() =>
        throwError(() => new ApiError('validation', 'Error en la plantilla (línea 1, columna 8): la función «foo» no existe.')),
      );
      fixture.detectChanges();
      type('#emitter-template', '{"t": {{foo()}}}');

      click('Vista previa');

      expect(el('.preview-error').textContent).toContain('Error en la plantilla (línea 1, columna 8)');
      expect(root.querySelector('.preview-body')).toBeNull();
    });

    it('asks for a template instead of calling the server when it is empty', () => {
      const previewer = vi.fn(() => of({ body: '', bytes: 0 }));
      host.previewer.set(previewer);
      fixture.detectChanges();

      click('Vista previa');

      expect(previewer).not.toHaveBeenCalled();
      expect(el('.preview-error').textContent).toContain('Escribe la plantilla');
    });

    it('discards the example when the template changes', () => {
      type('#emitter-template', 'a');
      click('Vista previa');
      expect(root.querySelector('.preview-body')).not.toBeNull();

      type('#emitter-template', 'ab');

      expect(root.querySelector('.preview-body')).toBeNull();
    });
  });

  it('clears the form with reset()', () => {
    type('#emitter-name', 'a');
    type('#emitter-template', 'x');
    submit();

    host.form().reset();
    fixture.detectChanges();

    expect(el<HTMLInputElement>('#emitter-name').value).toBe('');
    expect(el<HTMLTextAreaElement>('#emitter-template').value).toBe('');
    expect(el<HTMLInputElement>('#emitter-fixed').value).toBe('1000');
    expect(errors()).toEqual([]);
  });
});
