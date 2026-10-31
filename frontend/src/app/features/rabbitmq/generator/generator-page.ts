import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Observable, Subject, catchError, forkJoin, merge, switchMap, timer } from 'rxjs';
import { ApiError } from '../../../core/http/api-error';
import { ConfirmDialog } from '../../../shared/confirm-dialog/confirm-dialog';
import { EmitterActionRequest, EmitterList } from '../../../shared/generator/emitter-list/emitter-list';
import { EmitterForm } from '../../../shared/generator/emitter-form/emitter-form';
import { EmitterFormValue, EmitterView, TemplatePreset } from '../../../shared/generator/emitter-view';
import { RabbitMqConnection } from '../connection/rabbitmq-connection';
import { Exchange, ExchangesApi } from '../exchanges/exchanges-api';
import { Queue, QueuesApi } from '../queues/queues-api';
import {
  Emitter,
  GeneratorApi,
  GeneratorSettings,
  SimulatedConsumer,
  SimulatedConsumerRequest,
} from './generator-api';
import { RabbitMqDestinationForm } from './rabbitmq-destination-form';
import { ConsumerActionRequest, SimulatedConsumers } from './simulated-consumers';

/** Cada cuánto se vuelve a pedir el estado real mientras la pantalla está abierta (CA-5.2). */
export const REFRESH_MS = 2_000;

/** Mensajes listos a partir de los cuales se avisa de que una cola se está llenando. */
export const QUEUE_WARNING_THRESHOLD = 10_000;

/**
 * Área del generador de tráfico: emisores que publican sin parar y su límite global. Los emisores corren en el
 * backend, así que esta pantalla muestra siempre su estado real, también al abrirla de nuevo (CA-5.1).
 */
@Component({
  selector: 'app-generator-page',
  imports: [
    ReactiveFormsModule,
    EmitterList,
    EmitterForm,
    RabbitMqDestinationForm,
    SimulatedConsumers,
    ConfirmDialog,
  ],
  templateUrl: './generator-page.html',
  styleUrls: ['../../../shared/lab-area.css', './generator-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeneratorPage implements OnInit {
  private readonly api = inject(GeneratorApi);
  private readonly exchangesApi = inject(ExchangesApi);
  private readonly queuesApi = inject(QueuesApi);
  private readonly connection = inject(RabbitMqConnection);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = viewChild.required(ConfirmDialog);
  private readonly emitterForm = viewChild.required(EmitterForm);
  private readonly destinationForm = viewChild.required(RabbitMqDestinationForm);
  private readonly consumersSection = viewChild.required(SimulatedConsumers);

  /** Pide el estado de inmediato, sin esperar al siguiente intervalo. */
  private readonly refreshNow = new Subject<void>();

  /** Último estado recibido; se conserva aunque una consulta falle (CA-11.6). */
  protected readonly emitters = signal<Emitter[] | null>(null);
  protected readonly settings = signal<GeneratorSettings | null>(null);
  protected readonly consumers = signal<SimulatedConsumer[] | null>(null);
  protected readonly queues = signal<Queue[]>([]);
  protected readonly exchanges = signal<Exchange[]>([]);
  protected readonly presets = signal<TemplatePreset[]>([]);
  protected readonly creatingEmitter = signal(false);
  protected readonly emitterError = signal<string | null>(null);
  protected readonly creatingConsumer = signal(false);
  protected readonly consumerError = signal<string | null>(null);
  protected readonly busyConsumerIds = signal<ReadonlySet<string>>(new Set());
  /** Colas con más mensajes listos que el umbral, para avisar de que se están llenando. */
  protected readonly fullQueues = computed(() =>
    this.queues().filter((queue) => queue.ready > QUEUE_WARNING_THRESHOLD),
  );
  protected readonly warningThreshold = QUEUE_WARNING_THRESHOLD;
  protected readonly preview = (template: string) => this.api.previewTemplate(template);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busyIds = signal<ReadonlySet<string>>(new Set());
  protected readonly stoppingAll = signal(false);
  protected readonly savingLimit = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly limitError = signal<string | null>(null);

  protected readonly limit = new FormControl<number | null>(null);

  protected readonly destinationLabel = (emitter: EmitterView): string => {
    const destination = (emitter as Emitter).destination;
    if (!destination) {
      return '';
    }
    if (destination.type === 'QUEUE') {
      return `Cola «${destination.queue}»`;
    }
    const key = destination.routingKey ? ` · routing key «${destination.routingKey}»` : '';
    return `Exchange «${destination.exchange}»${key}`;
  };

  ngOnInit(): void {
    this.api
      .listTemplates()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (presets) => this.presets.set(presets),
        // sin catálogo se puede seguir escribiendo la plantilla a mano
        error: (error: ApiError) => this.connection.reportFailure(error),
      });

    merge(timer(0, REFRESH_MS), this.refreshNow)
      .pipe(
        // una consulta nueva sustituye a la anterior: nunca se acumulan
        switchMap(() =>
          forkJoin({
            emitters: this.api.listEmitters(),
            settings: this.api.getSettings(),
            consumers: this.api.listConsumers(),
            queues: this.queuesApi.list(),
            exchanges: this.exchangesApi.list(),
          }).pipe(
            catchError((error: ApiError) => {
              this.connection.reportFailure(error);
              this.loadError.set(error.message);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ emitters, settings, consumers, queues, exchanges }) => {
        this.emitters.set(emitters);
        this.settings.set(settings);
        this.consumers.set(consumers);
        this.queues.set(queues);
        this.exchanges.set(exchanges);
        this.loadError.set(null);
        // no se pisa lo que el usuario está escribiendo
        if (!this.limit.dirty) {
          this.limit.setValue(settings.maxRatePerSecond, { emitEvent: false });
        }
      });
  }

  protected limitValidationError(): string | null {
    const value = this.limit.value;
    const max = this.settings()?.absoluteMaxRatePerSecond ?? 500;
    if (value === null || !Number.isInteger(value) || value < 1 || value > max) {
      return `El límite debe ser un número entero entre 1 y ${max}.`;
    }
    return null;
  }

  protected act(request: EmitterActionRequest): void {
    const id = request.emitter.id;
    if (this.busyIds().has(id)) {
      return;
    }
    this.setBusy(id, true);
    this.notice.set(null);
    this.actionError.set(null);
    this.api
      .actOnEmitter(id, request.action)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.emitters.update((list) => (list ?? []).map((emitter) => (emitter.id === id ? updated : emitter)));
          this.setBusy(id, false);
          this.refreshNow.next();
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.actionError.set(error.message);
          this.setBusy(id, false);
          this.refreshNow.next();
        },
      });
  }

  protected onPreset(preset: TemplatePreset): void {
    this.destinationForm().suggestRoutingKey(preset.suggestedRoutingKey);
  }

  protected createEmitter(value: EmitterFormValue): void {
    const destination = this.destinationForm().read();
    if (!destination || this.creatingEmitter()) {
      return;
    }
    this.creatingEmitter.set(true);
    this.emitterError.set(null);
    this.notice.set(null);
    this.api
      .createEmitter({ ...value, destination })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (emitter) => {
          this.creatingEmitter.set(false);
          this.emitterForm().reset();
          this.destinationForm().reset();
          this.notice.set(`Emisor «${emitter.name}» creado. Está parado: pulsa «Iniciar» para que empiece a publicar.`);
          this.refreshNow.next();
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.emitterError.set(error.message);
          this.creatingEmitter.set(false);
        },
      });
  }

  protected createConsumer(request: SimulatedConsumerRequest): void {
    if (this.creatingConsumer()) {
      return;
    }
    this.creatingConsumer.set(true);
    this.consumerError.set(null);
    this.notice.set(null);
    this.api
      .createConsumer(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (consumer) => {
          this.creatingConsumer.set(false);
          this.consumersSection().reset();
          this.notice.set(`Consumidor «${consumer.name}» creado. Está parado: pulsa «Iniciar» para que empiece a consumir.`);
          this.refreshNow.next();
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.consumerError.set(error.message);
          this.creatingConsumer.set(false);
        },
      });
  }

  protected actOnConsumer(request: ConsumerActionRequest): void {
    this.runOnConsumer(request.consumer.id, this.api.actOnConsumer(request.consumer.id, request.action), () => undefined);
  }

  protected async removeConsumer(consumer: SimulatedConsumer): Promise<void> {
    if (this.busyConsumerIds().has(consumer.id)) {
      return;
    }
    const confirmed = await this.dialog().open({
      title: `Eliminar el consumidor «${consumer.name}»`,
      message: 'Se parará y se eliminará. Los mensajes que quedan en la cola no se tocan.',
      confirmLabel: 'Eliminar',
    });
    if (!confirmed) {
      return;
    }
    this.runOnConsumer(consumer.id, this.api.deleteConsumer(consumer.id), () =>
      this.notice.set(`Consumidor «${consumer.name}» eliminado.`),
    );
  }

  private runOnConsumer(id: string, operation: Observable<unknown>, done: () => void): void {
    if (this.busyConsumerIds().has(id)) {
      return;
    }
    this.busyConsumerIds.update((current) => new Set(current).add(id));
    this.notice.set(null);
    this.actionError.set(null);
    const release = () =>
      this.busyConsumerIds.update((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    operation.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        done();
        release();
        this.refreshNow.next();
      },
      error: (error: ApiError) => {
        this.connection.reportFailure(error);
        this.actionError.set(error.message);
        release();
        this.refreshNow.next();
      },
    });
  }

  protected async remove(emitter: EmitterView): Promise<void> {
    if (this.busyIds().has(emitter.id)) {
      return;
    }
    const confirmed = await this.dialog().open({
      title: `Eliminar el emisor «${emitter.name}»`,
      message:
        emitter.status === 'RUNNING' || emitter.status === 'PAUSED'
          ? 'El emisor está en marcha: se parará y se eliminará. Los mensajes que ya ha publicado se quedan en sus colas.'
          : 'Se eliminará el emisor. Los mensajes que ya ha publicado se quedan en sus colas.',
      confirmLabel: 'Eliminar',
    });
    if (!confirmed) {
      return;
    }
    this.setBusy(emitter.id, true);
    this.notice.set(null);
    this.actionError.set(null);
    this.api
      .deleteEmitter(emitter.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.emitters.update((list) => (list ?? []).filter((other) => other.id !== emitter.id));
          this.notice.set(`Emisor «${emitter.name}» eliminado.`);
          this.setBusy(emitter.id, false);
          this.refreshNow.next();
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.actionError.set(error.message);
          this.setBusy(emitter.id, false);
          this.refreshNow.next();
        },
      });
  }

  /** Para todos los emisores y consumidores simulados (CA-1.5). */
  protected stopAll(): void {
    if (this.stoppingAll()) {
      return;
    }
    this.stoppingAll.set(true);
    this.notice.set(null);
    this.actionError.set(null);
    this.api
      .stopAll()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.notice.set('Se ha parado todo lo que estaba en marcha.');
          this.stoppingAll.set(false);
          this.refreshNow.next();
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.actionError.set(error.message);
          this.stoppingAll.set(false);
        },
      });
  }

  protected changeLimit(): void {
    this.limit.markAsTouched();
    if (this.savingLimit() || this.limitValidationError()) {
      return;
    }
    this.savingLimit.set(true);
    this.limitError.set(null);
    this.notice.set(null);
    this.api
      .updateSettings(this.limit.value as number)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.settings.set(settings);
          this.limit.reset(settings.maxRatePerSecond);
          this.notice.set(`Límite cambiado a ${settings.maxRatePerSecond} mensajes por segundo.`);
          this.savingLimit.set(false);
        },
        error: (error: ApiError) => {
          this.connection.reportFailure(error);
          this.limitError.set(error.message);
          this.savingLimit.set(false);
        },
      });
  }

  protected formatRate(value: number): string {
    return Number.isInteger(value) ? String(value) : value.toFixed(1).replace('.', ',');
  }

  private setBusy(id: string, busy: boolean): void {
    this.busyIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }
}
