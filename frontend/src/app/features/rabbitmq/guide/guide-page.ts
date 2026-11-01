import { ChangeDetectionStrategy, Component, ElementRef, inject, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GuideExample } from './guide-example';
import { GUIDE_VIEWS, GuideNavigation, GuideView } from './guide-navigation';
import { ELEMENTS, GLOSSARY, IDEAS, TOPIC_EXAMPLES, TROUBLES } from './guide-content';

interface GuideSection {
  id: string;
  title: string;
}

/** Secciones de la guía, en el orden en que se leen. Cada una tiene su entrada en el índice. */
export const GUIDE_SECTIONS: readonly GuideSection[] = [
  { id: 'recorrido', title: 'El recorrido de un mensaje' },
  { id: 'elementos', title: 'Los elementos, uno a uno' },
  { id: 'tipos-de-exchange', title: 'Los tres tipos de exchange' },
  { id: 'ideas-que-confunden', title: 'Ideas que suelen confundir' },
  { id: 'si-algo-falla', title: 'Si algo no sale como esperas' },
  { id: 'glosario', title: 'Glosario' },
];

/**
 * Guía didáctica del Laboratorio RabbitMQ para quien parte de cero. Es contenido estático: no habla con el
 * backend ni con RabbitMQ, así que se puede leer aunque RabbitMQ no esté disponible.
 */
@Component({
  selector: 'app-guide-page',
  imports: [RouterLink, GuideExample],
  templateUrl: './guide-page.html',
  styleUrls: ['../../../shared/lab-area.css', './guide-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuidePage {
  private readonly navigation = inject(GuideNavigation);
  private readonly menu = viewChild<ElementRef<HTMLElement>>('menu');

  protected readonly views = GUIDE_VIEWS;
  protected readonly view = this.navigation.view;
  protected readonly sections = GUIDE_SECTIONS;
  protected readonly elements = ELEMENTS;
  protected readonly topicExamples = TOPIC_EXAMPLES;
  protected readonly ideas = IDEAS;
  protected readonly troubles = TROUBLES;
  protected readonly glossary = GLOSSARY;

  /** Cambia de parte y lleva la lectura al principio de la nueva. */
  protected show(view: GuideView): void {
    if (view === this.view()) {
      return;
    }
    this.navigation.view.set(view);
    // tras pintar la parte nueva, se sube hasta el submenú (la cabecera fija ya se tiene en cuenta en el CSS)
    queueMicrotask(() => this.menu()?.nativeElement.scrollIntoView?.({ block: 'start' }));
  }
}
