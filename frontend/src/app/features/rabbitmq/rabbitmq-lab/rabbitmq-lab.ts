import { ChangeDetectionStrategy, Component, OnInit, inject, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ConfirmDialog } from '../../../shared/confirm-dialog/confirm-dialog';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { ConsumerSessionService } from '../consumers/consumer-session.service';

interface LabArea {
  label: string;
  path: string;
}

/** Áreas del laboratorio, en el orden en que se ofrecen (CA-3.3). */
const AREAS: readonly LabArea[] = [
  { label: 'Exchanges', path: 'exchanges' },
  { label: 'Colas', path: 'queues' },
  { label: 'Bindings', path: 'bindings' },
  { label: 'Publicación', path: 'publish' },
  { label: 'Consumidores', path: 'consumers' },
];

/**
 * Contenedor del Laboratorio RabbitMQ: muestra el estado de la conexión (RF-2), ofrece las áreas como
 * pestañas y aloja la pantalla de cada una. Con RabbitMQ no disponible las operaciones se deshabilitan,
 * pero lo que ya estaba en pantalla se conserva (CA-11.6).
 */
@Component({
  selector: 'app-rabbitmq-lab',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ConfirmDialog],
  templateUrl: './rabbitmq-lab.html',
  styleUrl: './rabbitmq-lab.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RabbitMqLab implements OnInit {
  protected readonly connection = inject(RabbitMqConnection);
  protected readonly areas = AREAS;
  private readonly consumers = inject(ConsumerSessionService);
  private readonly dialog = viewChild.required(ConfirmDialog);

  ngOnInit(): void {
    this.connection.check();
  }

  /**
   * Pregunta antes de salir del laboratorio si hay consumidores activos (CA-9.14). Confirmar los detiene,
   * cerrando el flujo: RabbitMQ devuelve a la cola los mensajes pendientes. Cancelar deja al usuario aquí.
   * Cambiar de área dentro del laboratorio no pasa por aquí.
   */
  async confirmLeave(): Promise<boolean> {
    const active = this.consumers.activeConsumers();
    if (active.length === 0) {
      return true;
    }
    const confirmed = await this.dialog().open({
      title: 'Salir del Laboratorio RabbitMQ',
      message:
        active.length === 1
          ? 'Hay un consumidor activo. Al salir se detendrá y sus mensajes pendientes de confirmar volverán a la cola:'
          : `Hay ${active.length} consumidores activos. Al salir se detendrán y sus mensajes pendientes de confirmar volverán a la cola:`,
      items: active.map((consumer) => `${consumer.info.name} (cola «${consumer.info.queue}»)`),
      confirmLabel: 'Salir y detenerlos',
      cancelLabel: 'Quedarme',
    });
    if (confirmed) {
      this.consumers.stopAll();
    }
    return confirmed;
  }
}
