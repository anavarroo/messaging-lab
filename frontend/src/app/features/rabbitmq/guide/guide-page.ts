import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GuideExample } from './guide-example';
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
  { id: 'ejemplo', title: 'Ejemplo paso a paso: la pizzería' },
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
  styleUrls: ['../shared/lab-area.css', './guide-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuidePage {
  protected readonly sections = GUIDE_SECTIONS;
  protected readonly elements = ELEMENTS;
  protected readonly topicExamples = TOPIC_EXAMPLES;
  protected readonly ideas = IDEAS;
  protected readonly troubles = TROUBLES;
  protected readonly glossary = GLOSSARY;
}
