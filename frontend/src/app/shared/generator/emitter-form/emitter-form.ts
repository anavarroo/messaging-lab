import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { EmitterFormValue, Interval, PreviewResult, TemplatePreset, describeInterval, maxPerSecond } from '../emitter-view';
import { toInterval, validateInterval } from '../interval-validation';

/**
 * Parte de un emisor que no depende del sistema de mensajería: nombre, frecuencia, plantilla del mensaje, límites y
 * vista previa. El destino lo aporta quien usa el formulario, proyectado en el hueco `[destination]`.
 */
@Component({
  selector: 'app-emitter-form',
  imports: [ReactiveFormsModule],
  templateUrl: './emitter-form.html',
  styleUrls: ['../../lab-area.css', './emitter-form.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmitterForm {
  private readonly destroyRef = inject(DestroyRef);

  readonly presets = input<TemplatePreset[]>([]);
  /** Genera un ejemplo del mensaje con la plantilla escrita; lo aporta quien conoce el servidor. */
  readonly previewer = input.required<(template: string) => Observable<PreviewResult>>();
  readonly saving = input(false);
  /** Error que ha devuelto el servidor al crear el emisor (por ejemplo, una plantilla inválida). */
  readonly submitError = input<string | null>(null);

  /** Valores válidos del formulario, al pulsar «Crear emisor». */
  readonly submitted = output<EmitterFormValue>();
  /** Se ha cargado una plantilla del catálogo; quien aporta el destino puede aprovechar su clave sugerida. */
  readonly presetApplied = output<TemplatePreset>();

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    mode: new FormControl<Interval['mode']>('FIXED', { nonNullable: true }),
    fixedMs: new FormControl<number | null>(1000),
    minMs: new FormControl<number | null>(null),
    maxMs: new FormControl<number | null>(null),
    template: new FormControl('', { nonNullable: true }),
    maxMessages: new FormControl<number | null>(null),
    maxDurationSeconds: new FormControl<number | null>(null),
    onlyWhenChanged: new FormControl(false, { nonNullable: true }),
  });
  protected readonly presetId = new FormControl('', { nonNullable: true });

  protected readonly attempted = signal(false);
  protected readonly previewing = signal(false);
  protected readonly preview = signal<PreviewResult | null>(null);
  protected readonly previewError = signal<string | null>(null);
  /** Se incrementa con cada cambio del formulario para que las validaciones se recalculen. */
  private readonly changes = signal(0);

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.changes.update((n) => n + 1));
    // al cambiar la plantilla, el ejemplo mostrado deja de corresponderse con ella
    this.form.controls.template.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.preview.set(null);
      this.previewError.set(null);
    });
  }

  protected errors() {
    this.changes();
    const v = this.form.getRawValue();
    return {
      name: v.name.trim() ? null : 'Escribe un nombre para el emisor.',
      interval: validateInterval(v.mode, v.fixedMs, v.minMs, v.maxMs, 'la frecuencia'),
      template: v.template.trim() ? null : 'Escribe la plantilla del mensaje o elige una del catálogo.',
      maxMessages: this.positiveOrEmpty(v.maxMessages, 'El número máximo de mensajes'),
      maxDuration: this.positiveOrEmpty(v.maxDurationSeconds, 'La duración máxima'),
    };
  }

  private positiveOrEmpty(value: number | null, subject: string): string | null {
    return value === null || (Number.isInteger(value) && value >= 1) ? null : `${subject} debe ser un número entero mayor que 0.`;
  }

  protected frequencySummary(): string | null {
    this.changes();
    const v = this.form.getRawValue();
    if (validateInterval(v.mode, v.fixedMs, v.minMs, v.maxMs, 'la frecuencia')) {
      return null;
    }
    const interval = toInterval(v.mode, v.fixedMs, v.minMs, v.maxMs);
    const rate = maxPerSecond(interval);
    const text = Number.isInteger(rate) ? String(rate) : rate.toFixed(1).replace('.', ',');
    return `Publicará ${describeInterval(interval)}: hasta ${text} mensajes por segundo.`;
  }

  protected applyPreset(): void {
    const preset = this.presets().find((candidate) => candidate.id === this.presetId.value);
    if (!preset) {
      return;
    }
    const interval = preset.suggestedInterval;
    this.form.patchValue({
      template: preset.template,
      mode: interval.mode,
      fixedMs: interval.mode === 'FIXED' ? interval.fixedMs : 1000,
      minMs: interval.mode === 'RANGE' ? interval.minMs : null,
      maxMs: interval.mode === 'RANGE' ? interval.maxMs : null,
      onlyWhenChanged: preset.onlyWhenChanged,
      name: this.form.controls.name.value.trim() ? this.form.controls.name.value : preset.name,
    });
    this.preview.set(null);
    this.previewError.set(null);
    this.presetApplied.emit(preset);
  }

  protected showPreview(): void {
    const template = this.form.controls.template.value;
    if (!template.trim()) {
      this.previewError.set(this.errors().template);
      return;
    }
    if (this.previewing()) {
      return;
    }
    this.previewing.set(true);
    this.previewError.set(null);
    this.previewer()(template)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.preview.set(result);
          this.previewing.set(false);
        },
        error: (error: ApiError) => {
          this.preview.set(null);
          this.previewError.set(error.message);
          this.previewing.set(false);
        },
      });
  }

  protected submit(): void {
    this.attempted.set(true);
    if (this.saving() || Object.values(this.errors()).some((message) => message !== null)) {
      return;
    }
    const v = this.form.getRawValue();
    this.submitted.emit({
      name: v.name.trim(),
      interval: toInterval(v.mode, v.fixedMs, v.minMs, v.maxMs),
      template: v.template,
      maxMessages: v.maxMessages,
      maxDurationSeconds: v.maxDurationSeconds,
      onlyWhenChanged: v.onlyWhenChanged,
    });
  }

  /** Vacía el formulario tras crear el emisor. */
  reset(): void {
    this.form.reset({ mode: 'FIXED', fixedMs: 1000, onlyWhenChanged: false });
    this.presetId.reset('');
    this.attempted.set(false);
    this.preview.set(null);
    this.previewError.set(null);
  }
}
