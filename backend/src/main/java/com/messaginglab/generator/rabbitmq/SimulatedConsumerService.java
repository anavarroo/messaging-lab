package com.messaginglab.generator.rabbitmq;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.LabException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerConsumers;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.gateway.BrokerConsumers.ConsumerListener;
import com.messaginglab.generator.RateMeter;
import com.messaginglab.generator.Scheduler;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot.Status;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.StopReason;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Consumidores simulados: consumen una cola en modo manual, tardan un tiempo en procesar cada mensaje y fallan un
 * porcentaje de las veces. Corren en el backend, no están ligados a ningún navegador, y se pierden al apagarlo.
 * <p>
 * Reciben un mensaje cada vez (el consumo manual retiene uno), así que procesan en serie, como una persona o un
 * servicio lento: justo lo que hace falta para ver crecer una cola cuando el emisor va más rápido.
 */
public final class SimulatedConsumerService {

	public static final int MAX_CONSUMERS = 20;

	private static final int MAX_NAME_LENGTH = 255;

	private final BrokerConsumers broker;

	private final Scheduler scheduler;

	private final Clock clock;

	private final Supplier<RandomGenerator> randoms;

	/** Protegido por el cerrojo de este servicio; el estado de cada consumidor, por el suyo. */
	private final Map<String, Simulated> consumers = new LinkedHashMap<>();

	public SimulatedConsumerService(BrokerConsumers broker, Scheduler scheduler, Clock clock,
			Supplier<RandomGenerator> randoms) {
		this.broker = broker;
		this.scheduler = scheduler;
		this.clock = clock;
		this.randoms = randoms;
	}

	public synchronized List<SimulatedConsumerSnapshot> list() {
		return consumers.values().stream().map(Simulated::snapshot).toList();
	}

	public synchronized SimulatedConsumerSnapshot get(String id) {
		return find(id).snapshot();
	}

	public synchronized SimulatedConsumerSnapshot create(SimulatedConsumerSpec spec) {
		validate(spec, null);
		if (consumers.size() >= MAX_CONSUMERS) {
			throw new ConflictException(
					"Ya hay " + MAX_CONSUMERS + " consumidores simulados, que es el máximo. Elimina alguno antes.");
		}
		Simulated consumer = new Simulated(UUID.randomUUID().toString(), spec);
		consumers.put(consumer.id, consumer);
		return consumer.snapshot();
	}

	/** Edita un consumidor parado; sus contadores vuelven a cero. */
	public synchronized SimulatedConsumerSnapshot update(String id, SimulatedConsumerSpec spec) {
		Simulated consumer = find(id);
		if (consumer.isRunning()) {
			throw new ConflictException("Solo se puede editar un consumidor parado. Para «" + consumer.spec.name() + "» antes.");
		}
		validate(spec, consumer);
		consumer.replace(spec);
		return consumer.snapshot();
	}

	public synchronized void delete(String id) {
		Simulated consumer = find(id);
		consumer.stop();
		consumers.remove(id);
	}

	/** Empieza a consumir; falla si la cola no existe. Empieza de cero los contadores. */
	public synchronized SimulatedConsumerSnapshot start(String id) {
		Simulated consumer = find(id);
		if (consumer.isRunning()) {
			throw new ConflictException("«" + consumer.spec.name() + "» ya está en marcha.");
		}
		consumer.start();
		return consumer.snapshot();
	}

	public synchronized SimulatedConsumerSnapshot stop(String id) {
		Simulated consumer = find(id);
		if (!consumer.isRunning()) {
			throw new ConflictException("«" + consumer.spec.name() + "» ya está parado.");
		}
		consumer.stop();
		return consumer.snapshot();
	}

	public synchronized void stopAll() {
		consumers.values().stream().filter(Simulated::isRunning).forEach(Simulated::stop);
	}

	/** Para todo y libera el planificador, al apagar la aplicación. */
	public synchronized void shutdown() {
		stopAll();
		scheduler.shutdown();
	}

	private Simulated find(String id) {
		Simulated consumer = consumers.get(id);
		if (consumer == null) {
			throw new NotFoundException("El consumidor simulado no existe: puede que ya se haya eliminado.");
		}
		return consumer;
	}

	private void validate(SimulatedConsumerSpec spec, Simulated editing) {
		if (spec == null || spec.name() == null || spec.name().isBlank()) {
			throw new ValidationException("El nombre del consumidor simulado no puede estar vacío.");
		}
		if (spec.name().length() > MAX_NAME_LENGTH) {
			throw new ValidationException(
					"El nombre del consumidor simulado no puede superar los " + MAX_NAME_LENGTH + " caracteres.");
		}
		if (spec.name().chars().anyMatch(Character::isISOControl)) {
			throw new ValidationException("El nombre del consumidor simulado no puede contener caracteres de control.");
		}
		if (consumers.values().stream().anyMatch(other -> other != editing && other.spec.name().equals(spec.name()))) {
			throw new ConflictException("Ya hay un consumidor simulado con el nombre «" + spec.name() + "».");
		}
		if (spec.queue() == null || spec.queue().isEmpty()) {
			throw new ValidationException("Indica la cola que debe consumir.");
		}
		if (spec.processing() == null) {
			throw new ValidationException("Indica cuánto tarda en procesar cada mensaje.");
		}
		if (spec.failurePercent() < 0 || spec.failurePercent() > 100) {
			throw new ValidationException("El porcentaje de fallos debe estar entre 0 y 100.");
		}
		if (spec.onFailure() == null) {
			throw new ValidationException("Indica qué hacer con un mensaje que falla: reencolarlo o rechazarlo sin reencolar.");
		}
	}

	/** Un consumidor simulado y su estado; los campos mutables los protege su propio cerrojo. */
	private final class Simulated implements ConsumerListener {

		private final String id;

		private final RandomGenerator random = randoms.get();

		private final RateMeter meter = new RateMeter();

		private SimulatedConsumerSpec spec;

		private volatile Status status = Status.STOPPED;

		private long processed;

		private long failed;

		private String stopMessage;

		private ConsumerHandle handle;

		private long token;

		private Scheduler.Handle pending;

		private Simulated(String id, SimulatedConsumerSpec spec) {
			this.id = id;
			this.spec = spec;
		}

		private synchronized void replace(SimulatedConsumerSpec newSpec) {
			this.spec = newSpec;
			this.status = Status.STOPPED;
			resetCounters();
		}

		private void resetCounters() {
			processed = 0;
			failed = 0;
			stopMessage = null;
			meter.reset();
		}

		private boolean isRunning() {
			return status == Status.RUNNING;
		}

		/** Mientras se arranca, los mensajes que lleguen esperan al cerrojo hasta que el consumidor esté registrado. */
		private synchronized void start() {
			resetCounters();
			token++;
			handle = broker.start(spec.queue(), AckMode.MANUAL, this, spec.name());
			status = Status.RUNNING;
		}

		private synchronized void stop() {
			token++;
			if (pending != null) {
				pending.cancel();
				pending = null;
			}
			if (handle != null) {
				try {
					handle.stop();
				}
				catch (RuntimeException exception) {
					// ya estaba cerrado: RabbitMQ devuelve a la cola lo que tuviera pendiente
				}
				handle = null;
			}
			if (status == Status.RUNNING) {
				status = Status.STOPPED;
			}
		}

		@Override
		public synchronized void onMessage(DeliveredMessage message) {
			if (status != Status.RUNNING) {
				return;
			}
			long current = token;
			long delay = spec.processing().next(random);
			pending = scheduler.schedule(() -> process(message.deliveryTag(), current), delay);
		}

		private synchronized void process(long deliveryTag, long expectedToken) {
			if (status != Status.RUNNING || token != expectedToken) {
				return;
			}
			boolean fails = random.nextInt(100) < spec.failurePercent();
			MessageAction action = fails ? spec.onFailure().action() : MessageAction.ACK;
			try {
				handle.resolve(deliveryTag, action);
			}
			catch (LabException exception) {
				fail("Se ha parado: " + exception.getMessage());
				return;
			}
			if (fails) {
				failed++;
			}
			else {
				processed++;
			}
			meter.record(clock.millis());
		}

		@Override
		public synchronized void onStopped(StopReason reason) {
			if (status != Status.RUNNING) {
				return;
			}
			fail(reason == StopReason.QUEUE_DELETED
					? "La cola «" + spec.queue() + "» se ha eliminado: el consumidor simulado se ha parado."
					: "Se ha perdido la comunicación con RabbitMQ: el consumidor simulado se ha parado y sus mensajes pendientes volverán a la cola.");
		}

		private void fail(String message) {
			token++;
			if (pending != null) {
				pending.cancel();
				pending = null;
			}
			if (handle != null) {
				try {
					handle.stop();
				}
				catch (RuntimeException exception) {
					// ya terminó: no hay nada que liberar
				}
				handle = null;
			}
			status = Status.FAILED;
			stopMessage = message;
		}

		private synchronized SimulatedConsumerSnapshot snapshot() {
			return new SimulatedConsumerSnapshot(id, spec, status, processed, failed, meter.perSecond(clock.millis()),
					stopMessage);
		}

	}

}
