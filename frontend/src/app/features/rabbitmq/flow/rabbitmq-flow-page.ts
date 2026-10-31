import { ChangeDetectionStrategy, Component } from '@angular/core';
import { FLOW_SOURCE, FlowVocabulary } from '../../../shared/flow/flow-model';
import { FlowView } from '../../../shared/flow/flow-view/flow-view';
import { RabbitMqFlowSource } from './rabbitmq-flow-source';

/** Palabras de RabbitMQ para el visualizador, que por dentro es genérico. */
const RABBITMQ_WORDS: FlowVocabulary = {
  producer: 'Publicador',
  router: 'Exchange',
  destination: 'Cola',
  consumer: 'Consumidor',
  key: 'Routing key',
};

/** Pestaña «Flujo» del Laboratorio RabbitMQ: el visualizador compartido alimentado por el trazado de RabbitMQ. */
@Component({
  selector: 'app-rabbitmq-flow-page',
  imports: [FlowView],
  providers: [{ provide: FLOW_SOURCE, useClass: RabbitMqFlowSource }],
  template: `
    <section aria-labelledby="flow-title">
      <h3 id="flow-title">Flujo en tiempo real</h3>
      <p class="intro">
        Mira cómo viaja cada mensaje: quién publica, por qué exchange pasa, a qué colas llega y qué consumidor lo recibe.
        Los datos son reales: salen del <strong>trazado</strong> de RabbitMQ, que copia cada publicación y cada entrega,
        también las de otras aplicaciones que usen el mismo RabbitMQ. El trazado tiene un coste de rendimiento pensado para
        un laboratorio, no para producción.
      </p>
      <p class="help">
        Cuando un emisor publica directamente en una cola, el mensaje pasa por el «exchange por defecto» de RabbitMQ, un
        exchange sin nombre que lo entrega a la cola cuyo nombre coincide con la routing key. Los emisores y los consumidores
        simulados del generador aparecen con su propio nombre; el resto, con el nombre de su conexión.
      </p>
      <app-flow-view [vocabulary]="words" />
    </section>
  `,
  styleUrls: ['../../../shared/lab-area.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RabbitMqFlowPage {
  protected readonly words = RABBITMQ_WORDS;
}
