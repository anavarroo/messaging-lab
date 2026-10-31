import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ApiError } from '../../../core/http/api-error';
import { ConfirmDialog } from '../../../shared/confirm-dialog/confirm-dialog';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { asValidator, errorMessage, validateName } from '../validation/validation';
import { Exchange, ExchangeType, ExchangesApi } from './exchanges-api';

interface TypeOption {
  value: ExchangeType;
  label: string;
  help: string;
}

const TYPES: readonly TypeOption[] = [
  {
    value: 'DIRECT',
    label: 'Direct',
    help: 'Entrega a las colas cuya routing key de binding coincide exactamente con la del mensaje.',
  },
  {
    value: 'FANOUT',
    label: 'Fanout',
    help: 'Entrega a todas las colas enlazadas, sin mirar la routing key.',
  },
  {
    value: 'TOPIC',
    label: 'Topic',
    help: 'Entrega según un patrón: «*» sustituye una palabra y «#» cero o más, separadas por puntos.',
  },
];

/** Área de exchanges: listado, creación y borrado (RF-4). */
@Component({
  selector: 'app-exchanges-page',
  imports: [ReactiveFormsModule, ConfirmDialog],
  templateUrl: './exchanges-page.html',
  styleUrls: ['../../../shared/lab-area.css', './exchanges-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangesPage implements OnInit {
  private readonly api = inject(ExchangesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = viewChild.required(ConfirmDialog);

  protected readonly types = TYPES;
  protected readonly typeLabels: Record<ExchangeType, string> = {
    DIRECT: 'Direct',
    FANOUT: 'Fanout',
    TOPIC: 'Topic',
  };

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', asValidator((name) => validateName(name, 'del exchange'))],
    type: ['DIRECT' as ExchangeType],
  });

  /** Último listado recibido; se conserva aunque una operación o una recarga fallen (CA-11.6). */
  protected readonly exchanges = signal<Exchange[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  /** Hay una creación o un borrado en curso: se impide repetirlos (CA-11.5). */
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly submitted = signal(false);

  ngOnInit(): void {
    this.reload();
  }

  protected nameError(): string | null {
    return errorMessage(this.form.controls.name);
  }

  protected selectedHelp(): string | undefined {
    return TYPES.find((type) => type.value === this.form.controls.type.value)?.help;
  }

  protected displayName(exchange: Exchange): string {
    return exchange.name === '' ? '(exchange por defecto)' : exchange.name;
  }

  protected reload(): void {
    this.loading.set(true);
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (exchanges) => {
          this.exchanges.set(exchanges);
          this.loadError.set(null);
          this.loading.set(false);
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.loadError.set(error.message);
          this.loading.set(false);
        },
      });
  }

  protected create(): void {
    this.submitted.set(true);
    if (this.busy() || this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, type } = this.form.getRawValue();
    this.start();
    this.api
      .create(name, type)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.notice.set(
            result.created
              ? `Exchange «${name}» creado.`
              : `El exchange «${name}» ya existe con el tipo ${this.typeLabels[type]}; no se ha duplicado.`,
          );
          if (result.created) {
            this.form.controls.name.reset('');
            this.submitted.set(false);
          }
          this.finish();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  protected async remove(exchange: Exchange): Promise<void> {
    if (this.busy() || exchange.predefined) {
      return;
    }
    const confirmed = await this.dialog().open({
      title: `Eliminar el exchange «${exchange.name}»`,
      message:
        'Se eliminará el exchange y también todos sus bindings. Los mensajes que ya están en las colas no se pierden.',
      confirmLabel: 'Eliminar',
    });
    if (!confirmed) {
      return;
    }
    this.start();
    this.api
      .delete(exchange.name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set(`Exchange «${exchange.name}» eliminado.`);
          this.finish();
        },
        error: (error: ApiError) => this.fail(error),
      });
  }

  private start(): void {
    this.busy.set(true);
    this.notice.set(null);
    this.actionError.set(null);
  }

  /** Termina una operación correcta y recarga el listado (CA-4.2). */
  private finish(): void {
    this.busy.set(false);
    this.reload();
  }

  private fail(error: ApiError): void {
    this.connection.reportFailure(error);
    this.actionError.set(error.message);
    this.busy.set(false);
    // el listado puede haber cambiado (por ejemplo, un exchange que ya no existe): se refresca sin borrar lo mostrado
    if (error.kind === 'not-found' || error.kind === 'conflict') {
      this.reload();
    }
  }
}
