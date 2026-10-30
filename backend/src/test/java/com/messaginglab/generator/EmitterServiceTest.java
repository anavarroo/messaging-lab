package com.messaginglab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.support.FakeMessagePublisher;
import com.messaginglab.support.FakeMessagePublisher.TestDestination;
import com.messaginglab.support.ManualScheduler;
import com.messaginglab.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmitterServiceTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final FakeMessagePublisher publisher = new FakeMessagePublisher();

	private final GeneratorLimits limits = new GeneratorLimits();

	private EmitterService service;

	@BeforeEach
	void setUp() {
		service = new EmitterService(publisher, scheduler, clock, () -> new Random(1), limits);
	}

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	private static EmitterSpec spec(String name, Interval interval) {
		return new EmitterSpec(name, new TestDestination("destino"), interval, "{\"n\": {{contador}}}", null, null, false);
	}

	private EmitterSnapshot started(EmitterSpec spec) {
		return service.start(service.create(spec).id());
	}

	private void pass(long ms) {
		scheduler.advance(Duration.ofMillis(ms));
	}

	// ---- frecuencia

	@Test
	void aFixedEmitterPublishesAtItsFrequencyStartingRightAway() {
		started(spec("fijo", Interval.fixed(500)));

		pass(0);
		assertThat(publisher.sent()).hasSize(1);
		pass(500);
		assertThat(publisher.sent()).hasSize(2);
		pass(1500);
		assertThat(publisher.sent()).hasSize(5);
	}

	@Test
	void eachMessageIsGeneratedFromTheTemplate() {
		started(spec("plantilla", Interval.fixed(100)));

		pass(300);

		assertThat(publisher.bodies()).containsExactly("{\"n\": 1}", "{\"n\": 2}", "{\"n\": 3}", "{\"n\": 4}");
	}

	@Test
	void aRangeEmitterWaitsARandomTimeInsideTheRange() {
		started(spec("rango", Interval.range(200, 800)));
		pass(0);

		long previous = clock.millis();
		for (int i = 0; i < 40; i++) {
			int before = publisher.sent().size();
			while (publisher.sent().size() == before) {
				pass(1);
			}
			long gap = clock.millis() - previous;
			assertThat(gap).isBetween(200L, 800L);
			previous = clock.millis();
		}
	}

	@Test
	void theFrequencyDoesNotDriftWhenPublishingTakesTime() {
		FakeMessagePublisher slow = new FakeMessagePublisher() {
			@Override
			public boolean publish(com.messaginglab.generator.Destination destination, String body) {
				clock.advance(Duration.ofMillis(3));
				return super.publish(destination, body);
			}
		};
		EmitterService slowService = new EmitterService(slow, scheduler, clock, () -> new Random(1), limits);
		slowService.start(slowService.create(spec("lento", Interval.fixed(10))).id());

		pass(1000);

		assertThat(slow.sent().size()).as("a 10 ms, cien por segundo aunque cada envío tarde 3 ms").isBetween(99, 102);
		slowService.shutdown();
	}

	@Test
	void whenPublishingIsSlowerThanTheFrequencyItPublishesBackToBackWithoutABurstLater() {
		FakeMessagePublisher slow = new FakeMessagePublisher() {
			@Override
			public boolean publish(com.messaginglab.generator.Destination destination, String body) {
				clock.advance(Duration.ofMillis(20));
				return super.publish(destination, body);
			}
		};
		EmitterService slowService = new EmitterService(slow, scheduler, clock, () -> new Random(1), limits);
		slowService.start(slowService.create(spec("saturado", Interval.fixed(10))).id());

		pass(1000);

		assertThat(slow.sent().size()).as("al máximo que aguanta: un envío cada 20 ms").isBetween(48, 52);
		slowService.shutdown();
	}

	@Test
	void nothingIsPublishedBeforeStarting() {
		service.create(spec("parado", Interval.fixed(10)));

		pass(10_000);

		assertThat(publisher.sent()).isEmpty();
		assertThat(service.list().get(0).status()).isEqualTo(EmitterStatus.STOPPED);
	}

	// ---- contadores

	@Test
	void countsThePublishedAndTheUnroutedMessages() {
		started(spec("cuenta", Interval.fixed(100)));
		pass(200);
		publisher.routed(false);
		pass(300);

		EmitterSnapshot snapshot = service.list().get(0);

		assertThat(snapshot.published()).isEqualTo(6);
		assertThat(snapshot.unrouted()).isEqualTo(3);
	}

	@Test
	void measuresTheRealRatePerSecond() {
		started(spec("ritmo", Interval.fixed(100)));
		pass(4000);

		assertThat(service.list().get(0).ratePerSecond()).isBetween(9.0, 11.0);
	}

	@Test
	void theRateDropsToZeroOnceItStopsPublishing() {
		EmitterSnapshot emitter = started(spec("ritmo", Interval.fixed(100)));
		pass(2000);
		service.stop(emitter.id());
		pass(10_000);

		assertThat(service.get(emitter.id()).ratePerSecond()).isZero();
	}

	// ---- control

	@Test
	void pausingStopsThePublishingAndResumingContinuesWithoutRestartingTheCounters() {
		EmitterSnapshot emitter = started(spec("pausa", Interval.fixed(100)));
		pass(200);
		service.pause(emitter.id());
		int duringPause = publisher.sent().size();

		pass(5000);
		assertThat(publisher.sent()).hasSize(duringPause);
		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.PAUSED);

		service.resume(emitter.id());
		pass(100);
		assertThat(publisher.sent()).hasSize(duringPause + 1);
		assertThat(publisher.bodies().get(duringPause)).isEqualTo("{\"n\": " + (duringPause + 1) + "}");
	}

	@Test
	void stoppingStopsThePublishing() {
		EmitterSnapshot emitter = started(spec("para", Interval.fixed(100)));
		pass(300);
		service.stop(emitter.id());
		int sent = publisher.sent().size();

		pass(10_000);

		assertThat(publisher.sent()).hasSize(sent);
		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.STOPPED);
		assertThat(scheduler.pending()).isZero();
	}

	@Test
	void anEmitterCanBeStartedAgainAfterStoppingAndStartsFromZero() {
		EmitterSnapshot emitter = started(spec("otra vez", Interval.fixed(100)));
		pass(300);
		service.stop(emitter.id());

		service.start(emitter.id());
		pass(0);

		assertThat(service.get(emitter.id()).published()).isEqualTo(1);
		assertThat(publisher.bodies().get(publisher.sent().size() - 1)).isEqualTo("{\"n\": 1}");
	}

	@Test
	void invalidTransitionsAreRejectedExplainingWhy() {
		EmitterSnapshot emitter = service.create(spec("estados", Interval.fixed(100)));

		assertThatThrownBy(() -> service.pause(emitter.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("no está en marcha");
		assertThatThrownBy(() -> service.resume(emitter.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("no está en pausa");
		assertThatThrownBy(() -> service.stop(emitter.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("ya está parado");
		service.start(emitter.id());
		assertThatThrownBy(() -> service.start(emitter.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("ya está en marcha");
	}

	@Test
	void deletingARunningEmitterStopsItAndRemovesIt() {
		EmitterSnapshot emitter = started(spec("borrar", Interval.fixed(100)));
		pass(200);

		service.delete(emitter.id());
		int sent = publisher.sent().size();
		pass(5000);

		assertThat(publisher.sent()).hasSize(sent);
		assertThat(service.list()).isEmpty();
		assertThatThrownBy(() -> service.get(emitter.id())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void stopAllStopsEveryEmitterThatIsRunningOrPaused() {
		EmitterSnapshot a = started(spec("a", Interval.fixed(100)));
		started(spec("b", Interval.fixed(100)));
		service.pause(a.id());
		service.create(spec("c", Interval.fixed(100)));

		service.stopAll();
		int sent = publisher.sent().size();
		pass(5000);

		assertThat(publisher.sent()).hasSize(sent);
		assertThat(service.list()).extracting(EmitterSnapshot::status).containsOnly(EmitterStatus.STOPPED);
	}

	@Test
	void shuttingDownStopsEverythingAndTheScheduler() {
		started(spec("a", Interval.fixed(100)));

		service.shutdown();

		assertThat(scheduler.isShutdown()).isTrue();
		assertThat(service.list().get(0).status()).isEqualTo(EmitterStatus.STOPPED);
	}

	// ---- límites propios del emisor

	@Test
	void stopsByItselfAfterTheRequestedNumberOfMessages() {
		EmitterSnapshot emitter = started(new EmitterSpec("tres", new TestDestination("d"), Interval.fixed(100), "x", 3L, null, false));

		pass(10_000);

		assertThat(publisher.sent()).hasSize(3);
		EmitterSnapshot snapshot = service.get(emitter.id());
		assertThat(snapshot.status()).isEqualTo(EmitterStatus.FINISHED);
		assertThat(snapshot.stopMessage()).isEqualTo("Ha publicado los 3 mensajes que se le pidieron.");
		assertThat(scheduler.pending()).isZero();
	}

	@Test
	void stopsByItselfAfterTheRequestedDuration() {
		EmitterSnapshot emitter = started(new EmitterSpec("dura", new TestDestination("d"), Interval.fixed(100), "x", null, 2L, false));

		pass(10_000);

		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.FINISHED);
		assertThat(service.get(emitter.id()).stopMessage()).isEqualTo("Ha terminado tras 2 segundos en marcha.");
		assertThat(publisher.sent().size()).isBetween(19, 21);
	}

	@Test
	void theTimePausedDoesNotCountTowardsTheDuration() {
		EmitterSnapshot emitter = started(new EmitterSpec("pausada", new TestDestination("d"), Interval.fixed(100), "x", null, 2L, false));
		pass(1000);
		service.pause(emitter.id());
		pass(60_000);

		service.resume(emitter.id());
		pass(500);
		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.RUNNING);
		pass(1000);
		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.FINISHED);
	}

	@Test
	void anEmitterThatFinishedCanBeStartedAgain() {
		EmitterSnapshot emitter = started(new EmitterSpec("repite", new TestDestination("d"), Interval.fixed(100), "x", 2L, null, false));
		pass(1000);

		service.start(emitter.id());
		pass(1000);

		assertThat(publisher.sent()).hasSize(4);
	}

	@Test
	void onlyWhenChangedSkipsABodyEqualToThePreviousOne() {
		started(new EmitterSpec("plc", new TestDestination("d"), Interval.fixed(100), "{{elegir(A,A,A,B)}}", null, null, true));

		pass(10_000);

		java.util.List<String> bodies = publisher.bodies();
		assertThat(bodies).isNotEmpty();
		for (int i = 1; i < bodies.size(); i++) {
			assertThat(bodies.get(i)).isNotEqualTo(bodies.get(i - 1));
		}
		assertThat(bodies.size()).isLessThan(100);
	}

	@Test
	void withoutOnlyWhenChangedEqualBodiesAreAllPublished() {
		started(new EmitterSpec("todo", new TestDestination("d"), Interval.fixed(100), "igual", null, null, false));

		pass(1000);

		assertThat(publisher.bodies()).hasSize(11).containsOnly("igual");
	}

	// ---- destino y fallos

	@Test
	void anEmitterWhoseDestinationDoesNotExistCannotStart() {
		publisher.remove("fantasma");
		EmitterSnapshot emitter = service.create(new EmitterSpec("sin destino", new TestDestination("fantasma"), Interval.fixed(100), "x", null, null, false));

		assertThatThrownBy(() -> service.start(emitter.id())).isInstanceOf(NotFoundException.class);
		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.STOPPED);
	}

	@Test
	void whenTheDestinationDisappearsTheEmitterStopsAndSaysWhy() {
		EmitterSnapshot emitter = started(new EmitterSpec("pierde", new TestDestination("temporal"), Interval.fixed(100), "x", null, null, false));
		pass(250);

		publisher.remove("temporal");
		pass(1000);

		EmitterSnapshot snapshot = service.get(emitter.id());
		assertThat(snapshot.status()).isEqualTo(EmitterStatus.FAILED);
		assertThat(snapshot.stopMessage()).isEqualTo("Se ha parado porque el destino (el destino «temporal») ya no existe.");
		assertThat(scheduler.pending()).isZero();
		assertThat(publisher.sent()).hasSize(3);
	}

	@Test
	void anyOtherBrokerFailureAlsoStopsItWithTheReasonInSpanish() {
		EmitterSnapshot emitter = started(spec("cae", Interval.fixed(100)));
		pass(100);
		publisher.failWith(new BrokerUnavailableException("RabbitMQ no está disponible."));

		pass(500);

		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.FAILED);
		assertThat(service.get(emitter.id()).stopMessage()).isEqualTo("Se ha parado: RabbitMQ no está disponible.");
	}

	@Test
	void anUnexpectedErrorStopsItWithoutLeakingDetails() {
		EmitterSnapshot emitter = started(spec("raro", Interval.fixed(100)));
		publisher.failWith(new IllegalStateException("detalle interno"));

		pass(500);

		assertThat(service.get(emitter.id()).status()).isEqualTo(EmitterStatus.FAILED);
		assertThat(service.get(emitter.id()).stopMessage()).isEqualTo("Se ha parado por un error inesperado.");
	}

	@Test
	void aFailedEmitterDoesNotAffectTheOthers() {
		started(spec("sano", Interval.fixed(100)));
		publisher.remove("roto");
		EmitterSnapshot broken = service.create(new EmitterSpec("roto", new TestDestination("roto"), Interval.fixed(100), "x", null, null, false));
		publisher.remove("roto");

		assertThatThrownBy(() -> service.start(broken.id())).isInstanceOf(NotFoundException.class);
		pass(1000);

		assertThat(publisher.sent().size()).isGreaterThan(5);
	}

	// ---- alta, edición, validación

	@Test
	void createsAStoppedEmitterWithNoCounters() {
		EmitterSnapshot emitter = service.create(spec("nuevo", Interval.fixed(100)));

		assertThat(emitter.status()).isEqualTo(EmitterStatus.STOPPED);
		assertThat(emitter.published()).isZero();
		assertThat(emitter.id()).isNotBlank();
	}

	@Test
	void rejectsInvalidDefinitionsExplainingTheCause() {
		assertThatThrownBy(() -> service.create(spec(" ", Interval.fixed(100)))).isInstanceOf(ValidationException.class)
			.hasMessage("El nombre del emisor no puede estar vacío.");
		assertThatThrownBy(() -> service.create(spec("a".repeat(256), Interval.fixed(100)))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(spec("a\nb", Interval.fixed(100)))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(new EmitterSpec("x", null, Interval.fixed(100), "x", null, null, false)))
			.hasMessage("Indica el destino del emisor.");
		assertThatThrownBy(() -> service.create(new EmitterSpec("x", new TestDestination("d"), null, "x", null, null, false)))
			.hasMessage("Indica la frecuencia del emisor.");
		assertThatThrownBy(() -> service.create(new EmitterSpec("x", new TestDestination("d"), Interval.fixed(100), "x", 0L, null, false)))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(new EmitterSpec("x", new TestDestination("d"), Interval.fixed(100), "x", null, 0L, false)))
			.isInstanceOf(ValidationException.class);
		assertThat(service.list()).isEmpty();
	}

	@Test
	void rejectsAnInvalidTemplateWhenCreating() {
		assertThatThrownBy(() -> service.create(new EmitterSpec("x", new TestDestination("d"), Interval.fixed(100), "{{inventada}}", null, null, false)))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("la función «inventada» no existe");
	}

	@Test
	void namesAreUnique() {
		service.create(spec("repetido", Interval.fixed(100)));

		assertThatThrownBy(() -> service.create(spec("repetido", Interval.fixed(200)))).isInstanceOf(ConflictException.class)
			.hasMessage("Ya hay un emisor con el nombre «repetido».");
	}

	@Test
	void thereCanBeAtMost20Emitters() {
		for (int i = 0; i < EmitterService.MAX_EMITTERS; i++) {
			service.create(spec("e" + i, Interval.fixed(100)));
		}

		assertThatThrownBy(() -> service.create(spec("uno más", Interval.fixed(100)))).isInstanceOf(ConflictException.class)
			.hasMessageContaining("el máximo");
		service.delete(service.list().get(0).id());
		assertThat(service.create(spec("uno más", Interval.fixed(100)))).isNotNull();
	}

	@Test
	void editsAStoppedEmitterAndResetsItsCounters() {
		EmitterSnapshot emitter = started(spec("editable", Interval.fixed(100)));
		pass(500);
		service.stop(emitter.id());

		EmitterSnapshot edited = service.update(emitter.id(), spec("renombrado", Interval.fixed(250)));

		assertThat(edited.spec().name()).isEqualTo("renombrado");
		assertThat(edited.spec().interval()).isEqualTo(Interval.fixed(250));
		assertThat(edited.published()).isZero();
		assertThat(edited.status()).isEqualTo(EmitterStatus.STOPPED);
	}

	@Test
	void cannotEditARunningOrPausedEmitter() {
		EmitterSnapshot emitter = started(spec("ocupado", Interval.fixed(100)));

		assertThatThrownBy(() -> service.update(emitter.id(), spec("ocupado", Interval.fixed(200)))).isInstanceOf(ConflictException.class)
			.hasMessageContaining("Solo se puede editar un emisor parado");
		service.pause(emitter.id());
		assertThatThrownBy(() -> service.update(emitter.id(), spec("ocupado", Interval.fixed(200)))).isInstanceOf(ConflictException.class);
	}

	@Test
	void editingKeepsTheSameNameWithoutConflictingWithItself() {
		EmitterSnapshot emitter = service.create(spec("mismo", Interval.fixed(100)));

		assertThat(service.update(emitter.id(), spec("mismo", Interval.fixed(300))).spec().interval()).isEqualTo(Interval.fixed(300));
	}

	@Test
	void anUnknownEmitterIsNotFound() {
		assertThatThrownBy(() -> service.start("no-existe")).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.pause("no-existe")).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.delete("no-existe")).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.update("no-existe", spec("x", Interval.fixed(100)))).isInstanceOf(NotFoundException.class);
	}

	// ---- límite global (T02: RF-3)

	@Test
	void theDefaultLimitIs100MessagesPerSecondAndTheAbsoluteMaximumIs500() {
		assertThat(limits.maxRate()).isEqualTo(100);
		assertThat(GeneratorLimits.ABSOLUTE_MAX_RATE).isEqualTo(500);
		assertThat(EmitterService.MAX_EMITTERS).isEqualTo(20);
	}

	@Test
	void anEmitterThatWouldExceedTheLimitCannotStart() {
		started(spec("a", Interval.fixed(10)));
		EmitterSnapshot second = service.create(spec("b", Interval.fixed(10)));

		assertThatThrownBy(() -> service.start(second.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("añadiría 100 mensajes por segundo a los 100 que ya están en marcha")
			.hasMessageContaining("el límite es 100");
		assertThat(service.get(second.id()).status()).isEqualTo(EmitterStatus.STOPPED);
	}

	@Test
	void anEmitterThatFitsExactlyInTheLimitCanStart() {
		started(spec("a", Interval.fixed(20)));
		started(spec("b", Interval.fixed(20)));

		assertThat(service.ratePerSecondInUse()).isEqualTo(100.0);
	}

	@Test
	void theRangeCountsItsFastestCase() {
		started(spec("rango", Interval.range(10, 1000)));

		assertThat(service.ratePerSecondInUse()).isEqualTo(100.0);
	}

	@Test
	void pausedAndStoppedEmittersDoNotCountTowardsTheLimit() {
		EmitterSnapshot a = started(spec("a", Interval.fixed(10)));
		service.pause(a.id());

		assertThat(service.ratePerSecondInUse()).isZero();
		started(spec("b", Interval.fixed(10)));
	}

	@Test
	void resumingAlsoChecksTheLimit() {
		EmitterSnapshot a = started(spec("a", Interval.fixed(10)));
		service.pause(a.id());
		started(spec("b", Interval.fixed(10)));

		assertThatThrownBy(() -> service.resume(a.id())).isInstanceOf(ConflictException.class)
			.hasMessageContaining("límite");
		assertThat(service.get(a.id()).status()).isEqualTo(EmitterStatus.PAUSED);
	}

	@Test
	void raisingTheLimitLetsMoreEmittersStart() {
		started(spec("a", Interval.fixed(10)));
		EmitterSnapshot second = service.create(spec("b", Interval.fixed(10)));

		service.changeLimit(300);

		assertThat(service.start(second.id()).status()).isEqualTo(EmitterStatus.RUNNING);
		assertThat(limits.maxRate()).isEqualTo(300);
	}

	@Test
	void theLimitCannotBeLoweredBelowWhatIsAlreadyRunning() {
		started(spec("a", Interval.fixed(10)));

		assertThatThrownBy(() -> service.changeLimit(50)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("No se puede bajar el límite a 50")
			.hasMessageContaining("ya hay 100 en marcha");
		assertThat(limits.maxRate()).isEqualTo(100);
		service.changeLimit(100);
	}

	@Test
	void theLimitMustBeBetween1AndTheAbsoluteMaximum() {
		assertThatThrownBy(() -> service.changeLimit(0)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.changeLimit(-5)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.changeLimit(501)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("entre 1 y 500");
		service.changeLimit(500);
		service.changeLimit(1);
		assertThat(limits.maxRate()).isEqualTo(1);
	}

	@Test
	void anEmitterAtTheMaximumFrequencyCanRunAloneWhenTheLimitIsRaisedTo500() {
		service.changeLimit(500);

		started(spec("rapido", Interval.fixed(2)));
		pass(1000);

		assertThat(publisher.sent().size()).isBetween(490, 510);
	}

}
