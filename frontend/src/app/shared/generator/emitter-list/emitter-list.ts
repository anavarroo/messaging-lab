import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import {
  EMITTER_STATUS_LABELS,
  EmitterAction,
  EmitterView,
  describeInterval,
} from '../emitter-view';

/** Petición de una acción sobre un emisor. */
export interface EmitterActionRequest {
  emitter: EmitterView;
  action: EmitterAction;
}

/**
 * Lista de emisores con su estado, sus contadores y sus controles. No sabe a qué sistema de mensajería publican:
 * quien la usa le da el texto del destino de cada uno y se encarga de ejecutar las acciones.
 */
@Component({
  selector: 'app-emitter-list',
  templateUrl: './emitter-list.html',
  styleUrl: './emitter-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmitterList {
  readonly emitters = input.required<readonly EmitterView[]>();
  /** Texto del destino de un emisor, según el sistema de mensajería. */
  readonly destinationLabel = input.required<(emitter: EmitterView) => string>();
  /** Emisores con una operación en curso: sus botones se bloquean (CA-11.5). */
  readonly busyIds = input<ReadonlySet<string>>(new Set());
  readonly requested = output<EmitterActionRequest>();
  readonly removed = output<EmitterView>();

  protected readonly statusLabels = EMITTER_STATUS_LABELS;
  protected readonly intervalText = describeInterval;

  protected isBusy(emitter: EmitterView): boolean {
    return this.busyIds().has(emitter.id);
  }

  /** Puede ponerse en marcha un emisor que no esté ya en marcha ni en pausa. */
  protected canStart(emitter: EmitterView): boolean {
    return emitter.status === 'STOPPED' || emitter.status === 'FINISHED' || emitter.status === 'FAILED';
  }

  protected rate(emitter: EmitterView): string {
    return emitter.ratePerSecond.toFixed(1).replace('.', ',');
  }
}
