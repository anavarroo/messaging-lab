import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Exchange } from '../exchanges/exchanges-api';
import { Queue } from '../queues/queues-api';
import { EmitterDestination } from './generator-api';

/**
 * Destino de un emisor en RabbitMQ: un exchange con su routing key, o directamente una cola. Se proyecta dentro del
 * formulario compartido de emisores.
 */
@Component({
  selector: 'app-rabbitmq-destination-form',
  imports: [ReactiveFormsModule],
  templateUrl: './rabbitmq-destination-form.html',
  styleUrls: ['../../../shared/lab-area.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RabbitMqDestinationForm {
  readonly exchanges = input<readonly Exchange[]>([]);
  readonly queues = input<readonly Queue[]>([]);

  protected readonly form = new FormGroup({
    type: new FormControl<EmitterDestination['type']>('EXCHANGE', { nonNullable: true }),
    exchange: new FormControl('', { nonNullable: true }),
    routingKey: new FormControl('', { nonNullable: true }),
    queue: new FormControl('', { nonNullable: true }),
  });
  protected readonly attempted = signal(false);
  protected readonly typeValue = signal<EmitterDestination['type']>('EXCHANGE');

  constructor() {
    this.form.controls.type.valueChanges.subscribe((type) => this.typeValue.set(type));
  }

  protected error(): string | null {
    const v = this.form.getRawValue();
    if (v.type === 'EXCHANGE') {
      return v.exchange ? null : 'Elige el exchange al que publicar.';
    }
    return v.queue ? null : 'Elige la cola a la que publicar.';
  }

  /** Destino elegido; si falta algo, muestra el motivo y devuelve `null`. */
  read(): EmitterDestination | null {
    this.attempted.set(true);
    if (this.error()) {
      return null;
    }
    const v = this.form.getRawValue();
    return v.type === 'EXCHANGE'
      ? { type: 'EXCHANGE', exchange: v.exchange, routingKey: v.routingKey }
      : { type: 'QUEUE', queue: v.queue };
  }

  /** Propone una routing key (la de una plantilla del catálogo) sin pisar la que el usuario ya escribió. */
  suggestRoutingKey(key: string): void {
    if (!this.form.controls.routingKey.value.trim()) {
      this.form.controls.routingKey.setValue(key);
    }
  }

  reset(): void {
    this.form.reset({ type: 'EXCHANGE' });
    this.typeValue.set('EXCHANGE');
    this.attempted.set(false);
  }
}
