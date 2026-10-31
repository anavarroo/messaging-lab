import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  CLEANUP_STEPS,
  ExampleStep,
  FANOUT_STEPS,
  GENERATOR_CLEANUP_STEPS,
  GENERATOR_STEPS,
  MAIN_STEPS,
  NAMES,
  toParts,
} from './example-content';

/** Los tres ejemplos de la guía. */
export type ExamplePart = 'pizzeria' | 'variante' | 'generador';

/**
 * Un ejemplo paso a paso (RF-4, RF-6): cada paso dice qué hacer, qué se debería ver y qué ha pasado. El usuario hace
 * las operaciones él mismo en las pantallas del laboratorio; aquí no se ejecuta nada. La numeración empieza en 1 en
 * cada ejemplo.
 */
@Component({
  selector: 'app-guide-example',
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './guide-example.html',
  styleUrls: ['../../../shared/lab-area.css', './guide-example.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuideExample {
  readonly part = input.required<ExamplePart>();

  protected readonly names = NAMES;
  protected readonly parts = toParts;

  protected readonly steps = computed<readonly ExampleStep[]>(() => {
    switch (this.part()) {
      case 'pizzeria':
        return MAIN_STEPS;
      case 'variante':
        return FANOUT_STEPS;
      case 'generador':
        return GENERATOR_STEPS;
    }
  });

  /** Pasos opcionales para dejar el laboratorio limpio, al final del ejemplo 2 y del 3. */
  protected readonly cleanupSteps = computed<readonly ExampleStep[]>(() =>
    this.part() === 'variante' ? CLEANUP_STEPS : this.part() === 'generador' ? GENERATOR_CLEANUP_STEPS : [],
  );

  /** Número del paso dentro de su ejemplo; la limpieza continúa a los pasos del ejemplo. */
  protected number(step: ExampleStep): number {
    return [...this.steps(), ...this.cleanupSteps()].indexOf(step) + 1;
  }
}
