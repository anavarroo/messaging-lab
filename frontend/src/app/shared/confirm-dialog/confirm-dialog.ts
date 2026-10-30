import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  signal,
  viewChild,
} from '@angular/core';

/** Qué se le pide confirmar al usuario. */
export interface ConfirmRequest {
  title: string;
  /** Efecto de la acción, por ejemplo qué se perderá. */
  message: string;
  /** Elementos afectados, si los hay (por ejemplo las colas que usan una dead-letter queue). */
  items?: readonly string[];
  confirmLabel?: string;
  cancelLabel?: string;
}

/**
 * Diálogo de confirmación con el `<dialog>` nativo, que ya aporta el foco atrapado, el cierre con Escape y
 * la accesibilidad de un diálogo modal. Se abre con {@link open}, que devuelve si el usuario ha confirmado.
 * Cerrarlo de cualquier otro modo (cancelar, Escape) es no confirmar.
 */
@Component({
  selector: 'app-confirm-dialog',
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected readonly request = signal<ConfirmRequest | null>(null);

  private pending: ((confirmed: boolean) => void) | null = null;

  /** Abre el diálogo; si ya había una confirmación abierta, esa se da por no confirmada. */
  open(request: ConfirmRequest): Promise<boolean> {
    this.settle(false);
    this.request.set(request);
    return new Promise<boolean>((resolve) => {
      this.pending = resolve;
      this.dialog().nativeElement.showModal();
    });
  }

  protected confirm(): void {
    this.settle(true);
    this.dialog().nativeElement.close();
  }

  protected cancel(): void {
    this.dialog().nativeElement.close();
  }

  /** Se cierre como se cierre (botón, Escape...), si nadie ha confirmado es un «no». */
  protected closed(): void {
    this.settle(false);
  }

  private settle(confirmed: boolean): void {
    const resolve = this.pending;
    this.pending = null;
    resolve?.(confirmed);
  }
}
