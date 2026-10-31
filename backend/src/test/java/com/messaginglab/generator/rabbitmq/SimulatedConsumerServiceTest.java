package com.messaginglab.generator.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.generator.Interval;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot.Status;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec.FailureAction;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.StopReason;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.FakeBrokerConsumers.FakeConsumer;
import com.messaginglab.support.ManualScheduler;
import com.messaginglab.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedConsumerServiceTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final FakeBrokerConsumers broker = new FakeBrokerConsumers();

	private SimulatedConsumerService service;

	@BeforeEach
	void setUp() {
		service = new SimulatedConsumerService(broker, scheduler, clock, () -> new Random(1));
	}

	private static SimulatedConsumerSpec spec(String name, long processingMs, int failurePercent, FailureAction onFailure) {
		return new SimulatedConsumerSpec(name, "cola", Interval.fixed(processingMs), failurePercent, onFailure);
	}

	private SimulatedConsumerSnapshot started(SimulatedConsumerSpec spec) {
		return service.start(service.create(spec).id());
	}

	private FakeConsumer consumer() {
		return broker.last();
	}

	private void pass(long ms) {
		scheduler.advance(Duration.ofMillis(ms));
	}

	// ---- procesar

	@Test
	void startsTheConsumerWithItsNameSoItCanBeRecognisedInTheFlowVisualizer() {
		started(spec("cocinero", 100, 0, FailureAction.REJECT));

		assertThat(broker.lastName).isEqualTo("cocinero");
	}

	@Test
	void consumesTheQueueInManualModeSoItHoldsOneMessageAtATime() {
		started(spec("c", 100, 0, FailureAction.REJECT));

		assertThat(consumer().queue).isEqualTo("cola");
		assertThat(consumer().ackMode).isEqualTo(AckMode.MANUAL);
	}

	@Test
	void takesTheConfiguredTimeToProcessAMessageAndThenConfirmsIt() {
		started(spec("c", 500, 0, FailureAction.REJECT));
		long tag = consumer().deliver("uno");

		pass(499);
		assertThat(consumer().resolutions()).isEmpty();
		pass(1);

		assertThat(consumer().resolutions()).containsExactly(tag + ":ACK");
	}

	@Test
	void aRangeTakesARandomTimeInsideIt() {
		started(new SimulatedConsumerSpec("rango", "cola", Interval.range(100, 300), 0, FailureAction.REJECT));

		for (int i = 0; i < 40; i++) {
			int before = consumer().resolutions().size();
			consumer().deliver("m" + i);
			long waited = 0;
			while (consumer().resolutions().size() == before) {
				pass(1);
				waited++;
			}
			assertThat(waited).isBetween(100L, 300L);
		}
	}

	@Test
	void doesNotBlockTheThreadThatDeliversTheMessage() {
		started(spec("c", 1000, 0, FailureAction.REJECT));

		consumer().deliver("uno");

		assertThat(consumer().resolutions()).isEmpty();
		assertThat(scheduler.pending()).isEqualTo(1);
	}

	@Test
	void processesTheMessagesOneAfterTheOther() {
		started(spec("c", 100, 0, FailureAction.REJECT));

		for (int i = 0; i < 5; i++) {
			consumer().deliver("m" + i);
			pass(100);
		}

		assertThat(consumer().resolutions()).hasSize(5).allMatch(r -> r.endsWith(":ACK"));
		assertThat(service.list().get(0).processed()).isEqualTo(5);
	}

	// ---- fallos

	@Test
	void withZeroPercentNothingFails() {
		started(spec("c", 2, 0, FailureAction.REJECT));

		for (int i = 0; i < 200; i++) {
			consumer().deliver("m");
			pass(2);
		}

		assertThat(consumer().resolutions()).allMatch(r -> r.endsWith(":ACK"));
		assertThat(service.list().get(0).failed()).isZero();
	}

	@Test
	void withAHundredPercentEverythingFailsRequeueingIfAskedTo() {
		started(spec("c", 2, 100, FailureAction.REQUEUE));

		for (int i = 0; i < 20; i++) {
			consumer().deliver("m");
			pass(2);
		}

		assertThat(consumer().resolutions()).hasSize(20).allMatch(r -> r.endsWith(":REQUEUE"));
		SimulatedConsumerSnapshot snapshot = service.list().get(0);
		assertThat(snapshot.failed()).isEqualTo(20);
		assertThat(snapshot.processed()).isZero();
	}

	@Test
	void withAHundredPercentEverythingFailsRejectingWithoutRequeueingIfAskedTo() {
		started(spec("c", 2, 100, FailureAction.REJECT));

		for (int i = 0; i < 20; i++) {
			consumer().deliver("m");
			pass(2);
		}

		assertThat(consumer().resolutions()).hasSize(20).allMatch(r -> r.endsWith(":REJECT"));
	}

	@Test
	void theFailurePercentageIsApproximatelyRespected() {
		started(spec("c", 2, 30, FailureAction.REJECT));

		for (int i = 0; i < 2000; i++) {
			consumer().deliver("m");
			pass(2);
		}

		SimulatedConsumerSnapshot snapshot = service.list().get(0);
		assertThat(snapshot.failed()).isBetween(520L, 680L);
		assertThat(snapshot.processed() + snapshot.failed()).isEqualTo(2000);
	}

	@Test
	void everyFailureIsOneOfTheTwoActionsNeverBoth() {
		started(spec("c", 2, 50, FailureAction.REQUEUE));

		for (int i = 0; i < 300; i++) {
			consumer().deliver("m");
			pass(2);
		}

		assertThat(consumer().resolutions()).allMatch(r -> r.endsWith(":ACK") || r.endsWith(":REQUEUE"));
		assertThat(consumer().resolutions().stream().anyMatch(r -> r.endsWith(":ACK"))).isTrue();
		assertThat(consumer().resolutions().stream().anyMatch(r -> r.endsWith(":REQUEUE"))).isTrue();
	}

	// ---- contadores y ritmo

	@Test
	void measuresTheRealRate() {
		started(spec("c", 100, 0, FailureAction.REJECT));

		for (int i = 0; i < 40; i++) {
			consumer().deliver("m");
			pass(100);
		}

		assertThat(service.list().get(0).ratePerSecond()).isBetween(9.0, 11.0);
	}

	@Test
	void theRateIsZeroWhenItIsIdle() {
		started(spec("c", 100, 0, FailureAction.REJECT));
		consumer().deliver("m");
		pass(100);
		pass(30_000);

		assertThat(service.list().get(0).ratePerSecond()).isZero();
	}

	// ---- control

	@Test
	void stoppingStopsConsumingAndDiscardsTheMessageBeingProcessed() {
		SimulatedConsumerSnapshot created = started(spec("c", 1000, 0, FailureAction.REJECT));
		consumer().deliver("en proceso");

		service.stop(created.id());
		pass(5000);

		assertThat(consumer().stopped()).isTrue();
		assertThat(consumer().resolutions()).isEmpty();
		assertThat(service.get(created.id()).status()).isEqualTo(Status.STOPPED);
		assertThat(scheduler.pending()).isZero();
	}

	@Test
	void aMessageDeliveredAfterStoppingIsIgnored() {
		SimulatedConsumerSnapshot created = started(spec("c", 10, 0, FailureAction.REJECT));
		FakeConsumer first = consumer();
		service.stop(created.id());

		first.deliver("tarde");
		pass(100);

		assertThat(first.resolutions()).isEmpty();
	}

	@Test
	void startingAgainStartsFromZero() {
		SimulatedConsumerSnapshot created = started(spec("c", 2, 0, FailureAction.REJECT));
		consumer().deliver("m");
		pass(2);
		service.stop(created.id());

		service.start(created.id());

		SimulatedConsumerSnapshot snapshot = service.get(created.id());
		assertThat(snapshot.status()).isEqualTo(Status.RUNNING);
		assertThat(snapshot.processed()).isZero();
		assertThat(broker.started()).hasSize(2);
	}

	@Test
	void invalidTransitionsAreRejected() {
		SimulatedConsumerSnapshot created = service.create(spec("c", 10, 0, FailureAction.REJECT));

		assertThatThrownBy(() -> service.stop(created.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("ya está parado");
		service.start(created.id());
		assertThatThrownBy(() -> service.start(created.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("ya está en marcha");
	}

	@Test
	void deletingARunningConsumerStopsItAndRemovesIt() {
		SimulatedConsumerSnapshot created = started(spec("c", 10, 0, FailureAction.REJECT));

		service.delete(created.id());

		assertThat(consumer().stopped()).isTrue();
		assertThat(service.list()).isEmpty();
		assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void stopAllStopsEveryRunningConsumer() {
		started(spec("a", 10, 0, FailureAction.REJECT));
		started(spec("b", 10, 0, FailureAction.REJECT));
		service.create(spec("c", 10, 0, FailureAction.REJECT));

		service.stopAll();

		assertThat(broker.started()).allMatch(FakeConsumer::stopped);
		assertThat(service.list()).extracting(SimulatedConsumerSnapshot::status).containsOnly(Status.STOPPED);
	}

	@Test
	void shuttingDownStopsEverythingAndTheScheduler() {
		started(spec("a", 10, 0, FailureAction.REJECT));

		service.shutdown();

		assertThat(broker.last().stopped()).isTrue();
		assertThat(scheduler.isShutdown()).isTrue();
	}

	// ---- fin por causa ajena

	@Test
	void whenTheQueueIsDeletedItFailsAndSaysSo() {
		SimulatedConsumerSnapshot created = started(spec("c", 100, 0, FailureAction.REJECT));
		consumer().deliver("m");

		consumer().brokerStops(StopReason.QUEUE_DELETED);
		pass(1000);

		SimulatedConsumerSnapshot snapshot = service.get(created.id());
		assertThat(snapshot.status()).isEqualTo(Status.FAILED);
		assertThat(snapshot.stopMessage()).isEqualTo("La cola «cola» se ha eliminado: el consumidor simulado se ha parado.");
		assertThat(consumer().resolutions()).isEmpty();
		assertThat(scheduler.pending()).isZero();
	}

	@Test
	void whenTheConnectionIsLostItFailsAndSaysSo() {
		SimulatedConsumerSnapshot created = started(spec("c", 100, 0, FailureAction.REJECT));

		consumer().brokerStops(StopReason.CONNECTION_LOST);

		assertThat(service.get(created.id()).status()).isEqualTo(Status.FAILED);
		assertThat(service.get(created.id()).stopMessage()).contains("Se ha perdido la comunicación con RabbitMQ")
			.contains("volverán a la cola");
	}

	@Test
	void aFailedConsumerCanBeStartedAgain() {
		SimulatedConsumerSnapshot created = started(spec("c", 100, 0, FailureAction.REJECT));
		consumer().brokerStops(StopReason.QUEUE_DELETED);

		service.start(created.id());

		assertThat(service.get(created.id()).status()).isEqualTo(Status.RUNNING);
		assertThat(service.get(created.id()).stopMessage()).isNull();
	}

	@Test
	void ifRabbitMqRejectsTheResolutionItFailsWithTheReason() {
		SimulatedConsumerSnapshot created = started(spec("c", 10, 0, FailureAction.REJECT));
		consumer().deliver("m");
		consumer().failResolveWith(new BrokerUnavailableException("RabbitMQ no está disponible."));

		pass(10);

		assertThat(service.get(created.id()).status()).isEqualTo(Status.FAILED);
		assertThat(service.get(created.id()).stopMessage()).isEqualTo("Se ha parado: RabbitMQ no está disponible.");
		assertThat(consumer().stopped()).isTrue();
	}

	@Test
	void ifTheQueueDoesNotExistItCannotStart() {
		SimulatedConsumerSnapshot created = service.create(spec("c", 10, 0, FailureAction.REJECT));
		broker.failStartWith(new NotFoundException("La cola «cola» no existe."));

		assertThatThrownBy(() -> service.start(created.id())).isInstanceOf(NotFoundException.class);
		assertThat(service.get(created.id()).status()).isEqualTo(Status.STOPPED);
	}

	// ---- alta, edición, validación

	@Test
	void createsAStoppedConsumerWithNoCounters() {
		SimulatedConsumerSnapshot created = service.create(spec("nuevo", 10, 5, FailureAction.REQUEUE));

		assertThat(created.status()).isEqualTo(Status.STOPPED);
		assertThat(created.processed()).isZero();
		assertThat(created.failed()).isZero();
		assertThat(created.id()).isNotBlank();
		assertThat(broker.started()).isEmpty();
	}

	@Test
	void rejectsInvalidDefinitionsExplainingTheCause() {
		assertThatThrownBy(() -> service.create(spec(" ", 10, 0, FailureAction.REJECT))).isInstanceOf(ValidationException.class)
			.hasMessage("El nombre del consumidor simulado no puede estar vacío.");
		assertThatThrownBy(() -> service.create(spec("a".repeat(256), 10, 0, FailureAction.REJECT))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(spec("a\nb", 10, 0, FailureAction.REJECT))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(new SimulatedConsumerSpec("x", "", Interval.fixed(10), 0, FailureAction.REJECT)))
			.hasMessage("Indica la cola que debe consumir.");
		assertThatThrownBy(() -> service.create(new SimulatedConsumerSpec("x", "q", null, 0, FailureAction.REJECT)))
			.hasMessage("Indica cuánto tarda en procesar cada mensaje.");
		assertThatThrownBy(() -> service.create(spec("x", 10, -1, FailureAction.REJECT))).hasMessage("El porcentaje de fallos debe estar entre 0 y 100.");
		assertThatThrownBy(() -> service.create(spec("x", 10, 101, FailureAction.REJECT))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(new SimulatedConsumerSpec("x", "q", Interval.fixed(10), 0, null)))
			.isInstanceOf(ValidationException.class);
		assertThat(service.list()).isEmpty();
	}

	@Test
	void acceptsTheLimitsOfThePercentage() {
		service.create(spec("a", 10, 0, FailureAction.REJECT));
		service.create(spec("b", 10, 100, FailureAction.REJECT));

		assertThat(service.list()).hasSize(2);
	}

	@Test
	void namesAreUnique() {
		service.create(spec("repetido", 10, 0, FailureAction.REJECT));

		assertThatThrownBy(() -> service.create(spec("repetido", 20, 0, FailureAction.REJECT))).isInstanceOf(ConflictException.class)
			.hasMessage("Ya hay un consumidor simulado con el nombre «repetido».");
	}

	@Test
	void thereCanBeAtMost20() {
		for (int i = 0; i < SimulatedConsumerService.MAX_CONSUMERS; i++) {
			service.create(spec("c" + i, 10, 0, FailureAction.REJECT));
		}

		assertThatThrownBy(() -> service.create(spec("uno más", 10, 0, FailureAction.REJECT))).isInstanceOf(ConflictException.class)
			.hasMessageContaining("el máximo");
		service.delete(service.list().get(0).id());
		assertThat(service.create(spec("uno más", 10, 0, FailureAction.REJECT))).isNotNull();
	}

	@Test
	void editsAStoppedConsumerAndResetsItsCounters() {
		SimulatedConsumerSnapshot created = started(spec("c", 2, 0, FailureAction.REJECT));
		consumer().deliver("m");
		pass(2);
		service.stop(created.id());

		SimulatedConsumerSnapshot edited = service.update(created.id(), spec("renombrado", 50, 20, FailureAction.REQUEUE));

		assertThat(edited.spec().name()).isEqualTo("renombrado");
		assertThat(edited.spec().failurePercent()).isEqualTo(20);
		assertThat(edited.processed()).isZero();
	}

	@Test
	void cannotEditARunningConsumer() {
		SimulatedConsumerSnapshot created = started(spec("c", 10, 0, FailureAction.REJECT));

		assertThatThrownBy(() -> service.update(created.id(), spec("c", 20, 0, FailureAction.REJECT))).isInstanceOf(ConflictException.class)
			.hasMessageContaining("Solo se puede editar un consumidor parado");
	}

	@Test
	void anUnknownConsumerIsNotFound() {
		for (String id : List.of("x")) {
			assertThatThrownBy(() -> service.start(id)).isInstanceOf(NotFoundException.class);
			assertThatThrownBy(() -> service.stop(id)).isInstanceOf(NotFoundException.class);
			assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
		}
	}

}
