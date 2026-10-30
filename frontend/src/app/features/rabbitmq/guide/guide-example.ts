import { ChangeDetectionStrategy, Component } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CLEANUP_STEPS, ExampleStep, FANOUT_STEPS, MAIN_STEPS, NAMES, toParts } from './example-content';

/**
 * Ejemplo paso a paso de la pizzería (RF-4): cada paso dice qué hacer, qué se debería ver y qué ha pasado.
 * El usuario hace las operaciones él mismo en las pantallas del laboratorio; aquí no se ejecuta nada.
 */
@Component({
  selector: 'app-guide-example',
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './guide-example.html',
  styleUrls: ['../shared/lab-area.css', './guide-example.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuideExample {
  protected readonly names = NAMES;
  protected readonly mainSteps = MAIN_STEPS;
  protected readonly fanoutSteps = FANOUT_STEPS;
  protected readonly cleanupSteps = CLEANUP_STEPS;
  protected readonly parts = toParts;

  /** Número que se muestra de cada paso: continúa a lo largo de las tres partes. */
  protected number(step: ExampleStep): number {
    return [...MAIN_STEPS, ...FANOUT_STEPS, ...CLEANUP_STEPS].indexOf(step) + 1;
  }
}
