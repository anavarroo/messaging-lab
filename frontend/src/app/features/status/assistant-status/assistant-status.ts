import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { AssistantChat } from '../../assistant/assistant-chat';

/**
 * Estado del asistente de IA, en su propia tarjeta: el asistente no es de RabbitMQ (responderá también sobre Kafka).
 * Comparte el estado con el botón del chat de la cabecera.
 */
@Component({
  selector: 'app-assistant-status',
  templateUrl: './assistant-status.html',
  styleUrl: '../rabbitmq-status/rabbitmq-status.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantStatusCard implements OnInit {
  protected readonly chat = inject(AssistantChat);

  ngOnInit(): void {
    this.chat.refreshStatus();
  }
}
