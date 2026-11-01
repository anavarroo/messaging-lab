import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Particle } from '../flow-animator';
import { NODE_HEIGHT, NODE_WIDTH, Point, layoutFlow } from '../flow-logic';
import { DEFAULT_VOCABULARY, FlowNode, FlowTopology, FlowVocabulary } from '../flow-model';

interface DrawnNode {
  node: FlowNode;
  x: number;
  y: number;
  caption: string;
  detail: string;
}

interface DrawnEdge {
  key: string;
  from: Point;
  to: Point;
  label: string;
}

interface DrawnParticle {
  key: number;
  x: number;
  y: number;
  unrouted: boolean;
  opacity: number;
}

/**
 * Diagrama del recorrido de los mensajes: publicadores, enrutadores, destinos y consumidores con su ritmo actual y,
 * en los destinos, los mensajes listos. Solo dibuja; el movimiento de los puntos lo calcula {@link FlowAnimator}.
 */
@Component({
  selector: 'app-flow-diagram',
  templateUrl: './flow-diagram.html',
  styleUrl: './flow-diagram.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowDiagram {
  readonly topology = input.required<FlowTopology>();
  readonly particles = input<readonly Particle[]>([]);
  /** Mensajes por segundo del último segundo, por id de nodo. */
  readonly published = input<Readonly<Record<string, number>>>({});
  readonly delivered = input<Readonly<Record<string, number>>>({});
  readonly ready = input<Readonly<Record<string, number>>>({});
  readonly vocabulary = input<FlowVocabulary>(DEFAULT_VOCABULARY);

  protected readonly nodeWidth = NODE_WIDTH;
  protected readonly nodeHeight = NODE_HEIGHT;
  protected readonly layout = computed(() => layoutFlow(this.topology()));

  protected readonly nodes = computed<DrawnNode[]>(() => {
    const { positions } = this.layout();
    return this.topology().nodes.flatMap((node) => {
      const at = positions.get(node.id);
      return at ? [{ node, ...at, caption: this.caption(node), detail: this.detail(node) }] : [];
    });
  });

  protected readonly edges = computed<DrawnEdge[]>(() => {
    const { positions } = this.layout();
    return this.topology().edges.flatMap((edge) => {
      const from = positions.get(edge.from);
      const to = positions.get(edge.to);
      return from && to ? [{ key: `${edge.from}>${edge.to}`, from, to, label: edge.label ?? '' }] : [];
    });
  });

  protected readonly dots = computed<DrawnParticle[]>(() => {
    const { positions } = this.layout();
    return this.particles().flatMap((particle) => {
      const from = positions.get(particle.from);
      const to = positions.get(particle.to);
      if (!from || !to) {
        return [];
      }
      // una publicación sin destino se desvanece al llegar al enrutador
      const opacity = particle.unrouted ? 1 - particle.progress * 0.8 : 1;
      return [
        {
          key: particle.key,
          x: from.x + (to.x - from.x) * particle.progress,
          y: from.y + (to.y - from.y) * particle.progress,
          unrouted: particle.unrouted,
          opacity,
        },
      ];
    });
  });

  protected readonly summary = computed(() => {
    const count = (kind: FlowNode['kind']) => this.topology().nodes.filter((node) => node.kind === kind).length;
    const v = this.vocabulary();
    return (
      `Diagrama de flujo: ${count('PRODUCER')} ${v.producer.toLowerCase()}(es), ${count('ROUTER')} ${v.router.toLowerCase()}(s), ` +
      `${count('DESTINATION')} ${v.destination.toLowerCase()}(s) y ${count('CONSUMER')} ${v.consumer.toLowerCase()}(es). ` +
      'El registro de eventos describe cada mensaje en texto.'
    );
  });

  protected clip(text: string): string {
    return text.length > 26 ? `${text.slice(0, 25)}…` : text;
  }

  protected edgePath(edge: DrawnEdge): string {
    const middle = (edge.from.x + edge.to.x) / 2;
    return `M ${edge.from.x + NODE_WIDTH / 2} ${edge.from.y} C ${middle} ${edge.from.y}, ${middle} ${edge.to.y}, ${edge.to.x - NODE_WIDTH / 2} ${edge.to.y}`;
  }

  private caption(node: FlowNode): string {
    const v = this.vocabulary();
    const kind = { PRODUCER: v.producer, ROUTER: v.router, DESTINATION: v.destination, CONSUMER: v.consumer }[node.kind];
    // el detalle largo (por ejemplo, la dead-letter queue de una cola) queda en la ayuda del nodo, no en el recuadro
    return node.detail && node.kind === 'ROUTER' ? `${kind} · ${node.detail}` : kind;
  }

  /** Línea de números de un nodo: ritmo de entrada o salida y, en los destinos, mensajes listos. */
  private detail(node: FlowNode): string {
    const rate = (this.published()[node.id] ?? 0) + (this.delivered()[node.id] ?? 0);
    const text = `${Number.isInteger(rate) ? rate : rate.toFixed(1).replace('.', ',')}/s`;
    const ready = this.ready()[node.id];
    return node.kind === 'DESTINATION' && ready !== undefined ? `${text} · ${ready} listos` : text;
  }
}
