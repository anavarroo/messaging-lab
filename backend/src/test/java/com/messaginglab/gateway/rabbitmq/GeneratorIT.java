package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.EmitterSnapshot;
import com.messaginglab.generator.EmitterSpec;
import com.messaginglab.generator.EmitterStatus;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.Interval;
import com.messaginglab.generator.ThreadPoolScheduler;
import com.messaginglab.generator.rabbitmq.RabbitMqDestination;
import com.messaginglab.generator.rabbitmq.RabbitMqMessagePublisher;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot.Status;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec.FailureAction;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.QueueInspectionService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El generador de tráfico contra un RabbitMQ real: emisores y consumidores simulados con los adaptadores de verdad. */
class GeneratorIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AmqpConnectionProvider CONNECTIONS = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());

	private final RabbitMqTopology topology = new RabbitMqTopology(new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService bindings = new BindingService(topology);

	private final QueueInspectionService inspector = new QueueInspectionService(topology);

	private final List<String> createdExchanges = new ArrayList<>();

	private final List<String> createdQueues = new ArrayList<>();

	private EmitterService emitters;

	private SimulatedConsumerService simulated;

	@BeforeEach
	void setUp() {
		emitters = new EmitterService(new RabbitMqMessagePublisher(new AmqpPublisher(CONNECTIONS), topology),
				new ThreadPoolScheduler(8), Clock.systemUTC(), Random::new, new GeneratorLimits());
		simulated = new SimulatedConsumerService(new AmqpConsumers(CONNECTIONS), new ThreadPoolScheduler(4),
				Clock.systemUTC(), Random::new);
	}

	@AfterEach
	void cleanUp() {
		emitters.shutdown();
		simulated.shutdown();
		createdExchanges.forEach(name -> ignoreMissing(() -> exchanges.delete(name)));
		createdQueues.forEach(name -> ignoreMissing(() -> queues.delete(name)));
	}

	@AfterAll
	static void closeConnection() {
		CONNECTIONS.close();
	}

	// ---- emisores

	@Test
	void anEmitterPublishesAtItsFrequencyUntilItIsStopped() throws Exception {
		String queue = queue(null);
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("ritmo", RabbitMqDestination.toQueue(queue), Interval.fixed(50), "{\"n\": {{contador}}}")).id());

		Thread.sleep(2000);
		emitters.stop(emitter.id());

		EmitterSnapshot done = emitters.get(emitter.id());
		assertThat(done.status()).isEqualTo(EmitterStatus.STOPPED);
		assertThat(done.published()).isBetween(30L, 45L);
		assertThat(done.unrouted()).isZero();
		assertThat(ready(queue)).isEqualTo(done.published());
		long before = ready(queue);
		Thread.sleep(500);
		assertThat(ready(queue)).as("no publica más una vez parado").isEqualTo(before);
	}

	@Test
	void theMessagesCarryTheGeneratedBodyAndTheCounterIncreases() {
		String queue = queue(null);
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("plantilla", RabbitMqDestination.toQueue(queue), Interval.fixed(20),
				"{\"n\": {{contador}}, \"t\": {{numero(18,26,1)}}, \"ok\": {{booleano}}, \"ejes\": {{ejes(6,-3.14,3.14)}}}", 10L, null, false)).id());

		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FINISHED);

		QueueInspection inspection = inspector.inspect(queue);
		assertThat(inspection.messages()).hasSize(10);
		for (int i = 0; i < 10; i++) {
			JsonNode body = JSON.readTree(inspection.messages().get(i).body());
			assertThat(body.get("n").asInt()).isEqualTo(i + 1);
			assertThat(body.get("t").asDouble()).isBetween(18.0, 26.0);
			assertThat(body.get("ejes")).hasSize(6);
		}
	}

	@Test
	void anEmitterToATopicExchangeDeliversThroughTheBindingsAndCountsWhatNoQueueReceived() {
		String exchange = exchange(ExchangeType.TOPIC);
		String queue = queue(null);
		bindings.create(exchange, queue, "planta1.robot.*");
		EmitterSnapshot routed = emitters.start(emitters.create(new EmitterSpec("llega", RabbitMqDestination.toExchange(exchange, "planta1.robot.estado"),
				Interval.fixed(20), "x", 5L, null, false)).id());
		EmitterSnapshot lost = emitters.start(emitters.create(new EmitterSpec("se pierde", RabbitMqDestination.toExchange(exchange, "planta2.sensor"),
				Interval.fixed(20), "x", 5L, null, false)).id());

		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(routed.id()).status() == EmitterStatus.FINISHED
				&& emitters.get(lost.id()).status() == EmitterStatus.FINISHED);

		assertThat(emitters.get(routed.id()).unrouted()).isZero();
		assertThat(emitters.get(lost.id()).published()).isEqualTo(5);
		assertThat(emitters.get(lost.id()).unrouted()).isEqualTo(5);
		assertThat(ready(queue)).isEqualTo(5);
	}

	@Test
	void onlyWhenChangedDoesNotRepeatEqualBodiesInTheRealQueue() {
		String queue = queue(null);
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("plc", RabbitMqDestination.toQueue(queue), Interval.fixed(10),
				"{\"valor\": {{elegir(0,0,0,1)}}}", 15L, null, true)).id());
		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).published() >= 5);
		emitters.stop(emitter.id());

		List<String> bodies = inspector.inspect(queue).messages().stream().map(m -> m.body()).toList();

		for (int i = 1; i < bodies.size(); i++) {
			assertThat(bodies.get(i)).isNotEqualTo(bodies.get(i - 1));
		}
	}

	@Test
	void anEmitterStopsByItselfAndSaysWhyWhenItsQueueIsDeleted() {
		String queue = queue(null);
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("pierde la cola", RabbitMqDestination.toQueue(queue), Interval.fixed(50), "x")).id());
		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).published() >= 3);

		queues.delete(queue);

		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FAILED);
		assertThat(emitters.get(emitter.id()).stopMessage())
			.isEqualTo("Se ha parado porque el destino (la cola «" + queue + "») ya no existe.");
	}

	@Test
	void anEmitterStopsByItselfAndSaysWhyWhenItsExchangeIsDeleted() {
		String exchange = exchange(ExchangeType.DIRECT);
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("pierde el exchange", RabbitMqDestination.toExchange(exchange, "k"), Interval.fixed(50), "x")).id());
		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).published() >= 3);

		exchanges.delete(exchange);

		await().atMost(Duration.ofSeconds(10)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FAILED);
		assertThat(emitters.get(emitter.id()).stopMessage()).contains("ya no existe");
	}

	@Test
	void anEmitterCannotStartTowardsAnExchangeOrQueueThatDoesNotExist() {
		EmitterSnapshot toExchange = emitters.create(spec("a", RabbitMqDestination.toExchange("no-existe-" + UUID.randomUUID(), "k"), Interval.fixed(50), "x"));
		EmitterSnapshot toQueue = emitters.create(spec("b", RabbitMqDestination.toQueue("no-existe-" + UUID.randomUUID()), Interval.fixed(50), "x"));

		assertThatThrownBy(() -> emitters.start(toExchange.id())).isInstanceOf(NotFoundException.class).hasMessageContaining("El exchange");
		assertThatThrownBy(() -> emitters.start(toQueue.id())).isInstanceOf(NotFoundException.class).hasMessageContaining("La cola");
	}

	// ---- límite

	@Test
	void theGlobalLimitIsRespectedAndTheRealRateStaysWithinIt() throws Exception {
		String queue = queue(null);
		emitters.start(emitters.create(spec("uno", RabbitMqDestination.toQueue(queue), Interval.fixed(20), "x")).id());
		emitters.start(emitters.create(spec("dos", RabbitMqDestination.toQueue(queue), Interval.fixed(20), "x")).id());
		EmitterSnapshot third = emitters.create(spec("tres", RabbitMqDestination.toQueue(queue), Interval.fixed(20), "x"));

		assertThatThrownBy(() -> emitters.start(third.id())).isInstanceOf(ConflictException.class).hasMessageContaining("el límite es 100");
		assertThat(emitters.ratePerSecondInUse()).isEqualTo(100.0);
		long before = ready(queue);
		Thread.sleep(3000);

		long published = ready(queue) - before;
		assertThat(published).as("mensajes en 3 s con el límite de 100 por segundo").isBetween(200L, 330L);
	}

	@Test
	void theRealRateMatchesTheConfiguredFrequencyWithinTheSpecifiedMargin() throws Exception {
		String queue = queue(null);
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("cien", RabbitMqDestination.toQueue(queue), Interval.fixed(10), "x")).id());

		Thread.sleep(5000);
		emitters.stop(emitter.id());

		double perSecond = emitters.get(emitter.id()).published() / 5.0;
		System.out.printf("MEDIDA: emisor cada 10 ms (objetivo 100/s): %.1f por segundo%n", perSecond);
		assertThat(perSecond).as("desviación inferior al 20 % (RNF-2)").isBetween(80.0, 120.0);
	}

	@Test
	void whatTheRealPublisherSustainsAtTheHighestAllowedRate() throws Exception {
		String queue = queue(null);
		emitters.changeLimit(GeneratorLimits.ABSOLUTE_MAX_RATE);
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("maximo", RabbitMqDestination.toQueue(queue), Interval.fixed(2), "x")).id());

		Thread.sleep(4000);
		emitters.stop(emitter.id());

		EmitterSnapshot done = emitters.get(emitter.id());
		double perSecond = done.published() / 4.0;
		System.out.printf("MEDIDA: el emisor más rápido (cada 2 ms, objetivo 500/s) publicó %d mensajes en 4 s = %.0f por segundo%n", done.published(), perSecond);
		assertThat(perSecond).as("mensajes por segundo reales con el límite de 500").isBetween(200.0, 520.0);
		assertThat(ready(queue)).isEqualTo(done.published());
	}

	// ---- consumidores simulados

	@Test
	void aFastEmitterMakesTheQueueOfASlowConsumerGrow() throws Exception {
		String queue = queue(null);
		SimulatedConsumerSnapshot consumer = simulated.start(simulated.create(new SimulatedConsumerSpec("lento", queue, Interval.fixed(200), 0,
				FailureAction.REJECT)).id());
		EmitterSnapshot emitter = emitters.start(emitters.create(spec("rapido", RabbitMqDestination.toQueue(queue), Interval.fixed(20), "x")).id());

		Thread.sleep(3000);
		emitters.stop(emitter.id());

		Queue state = queues.list().stream().filter(q -> q.name().equals(queue)).findFirst().orElseThrow();
		SimulatedConsumerSnapshot slow = simulated.get(consumer.id());
		assertThat(state.ready()).as("los mensajes se acumulan: el emisor va a ~50/s y el consumidor a ~5/s").isGreaterThan(80);
		assertThat(slow.processed()).isBetween(8L, 20L);
		assertThat(slow.ratePerSecond()).isBetween(3.0, 7.0);
		assertThat(state.unacknowledged()).isLessThanOrEqualTo(1);
	}

	@Test
	void aConsumerThatKeepsUpDrainsTheQueue() {
		String queue = queue(null);
		simulated.start(simulated.create(new SimulatedConsumerSpec("rapido", queue, Interval.fixed(5), 0, FailureAction.REJECT)).id());
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("pocos", RabbitMqDestination.toQueue(queue), Interval.fixed(50),
				"x", 20L, null, false)).id());

		await().atMost(Duration.ofSeconds(15)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FINISHED);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ready(queue)).isZero());
		assertThat(simulated.list().get(0).processed()).isEqualTo(20);
	}

	@Test
	void aSimulatedConsumerWithFailuresSendsTheRejectedMessagesToTheDeadLetterQueue() {
		String dead = queue(null);
		String queue = queue(dead);
		simulated.start(simulated.create(new SimulatedConsumerSpec("falla", queue, Interval.fixed(5), 100, FailureAction.REJECT)).id());
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("diez", RabbitMqDestination.toQueue(queue), Interval.fixed(30),
				"{\"n\": {{contador}}}", 10L, null, false)).id());

		await().atMost(Duration.ofSeconds(15)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FINISHED);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(ready(dead)).isEqualTo(10));
		assertThat(ready(queue)).isZero();
		SimulatedConsumerSnapshot snapshot = simulated.list().get(0);
		assertThat(snapshot.failed()).isEqualTo(10);
		assertThat(snapshot.processed()).isZero();
		var message = inspector.inspect(dead).messages().get(0);
		assertThat(message.deathInfo().originQueue()).isEqualTo(queue);
		assertThat(message.deathInfo().reason()).isEqualTo("rejected");
	}

	@Test
	void withoutADeadLetterQueueRejectedMessagesAreDiscarded() {
		String queue = queue(null);
		simulated.start(simulated.create(new SimulatedConsumerSpec("falla", queue, Interval.fixed(5), 100, FailureAction.REJECT)).id());
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("cinco", RabbitMqDestination.toQueue(queue), Interval.fixed(30),
				"x", 5L, null, false)).id());

		await().atMost(Duration.ofSeconds(15)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FINISHED);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(simulated.list().get(0).failed()).isEqualTo(5));
		assertThat(ready(queue)).isZero();
	}

	@Test
	void failuresThatRequeueKeepTheMessagesInTheQueueAndTheyComeBackRedelivered() throws Exception {
		String queue = queue(null);
		simulated.start(simulated.create(new SimulatedConsumerSpec("reintenta", queue, Interval.fixed(10), 100, FailureAction.REQUEUE)).id());
		EmitterSnapshot emitter = emitters.start(emitters.create(new EmitterSpec("tres", RabbitMqDestination.toQueue(queue), Interval.fixed(30),
				"x", 3L, null, false)).id());
		await().atMost(Duration.ofSeconds(15)).until(() -> emitters.get(emitter.id()).status() == EmitterStatus.FINISHED);

		Thread.sleep(1500);

		SimulatedConsumerSnapshot snapshot = simulated.list().get(0);
		assertThat(snapshot.processed()).isZero();
		assertThat(snapshot.failed()).as("reintenta los mismos mensajes una y otra vez").isGreaterThan(20);
		Queue state = queues.list().stream().filter(q -> q.name().equals(queue)).findFirst().orElseThrow();
		assertThat(state.total()).isEqualTo(3);
	}

	@Test
	void whenTheQueueIsDeletedTheSimulatedConsumerFailsAndSaysSo() {
		String queue = queue(null);
		SimulatedConsumerSnapshot consumer = simulated.start(simulated.create(new SimulatedConsumerSpec("huerfano", queue, Interval.fixed(10), 0,
				FailureAction.REJECT)).id());

		queues.delete(queue);

		await().atMost(Duration.ofSeconds(10)).until(() -> simulated.get(consumer.id()).status() == Status.FAILED);
		assertThat(simulated.get(consumer.id()).stopMessage()).contains("se ha eliminado");
	}

	@Test
	void aSimulatedConsumerCannotStartOnAQueueThatDoesNotExist() {
		SimulatedConsumerSnapshot consumer = simulated.create(new SimulatedConsumerSpec("c", "no-existe-" + UUID.randomUUID(), Interval.fixed(10), 0,
				FailureAction.REJECT));

		assertThatThrownBy(() -> simulated.start(consumer.id())).isInstanceOf(NotFoundException.class);
		assertThat(simulated.get(consumer.id()).status()).isEqualTo(Status.STOPPED);
	}

	// ---- parar todo

	@Test
	void stoppingEverythingStopsThePublishingAndTheConsumptionForReal() throws Exception {
		String queue = queue(null);
		simulated.start(simulated.create(new SimulatedConsumerSpec("c", queue, Interval.fixed(10), 0, FailureAction.REJECT)).id());
		emitters.start(emitters.create(spec("e", RabbitMqDestination.toQueue(queue), Interval.fixed(20), "x")).id());
		Thread.sleep(500);

		emitters.stopAll();
		simulated.stopAll();
		Thread.sleep(500);
		long processed = simulated.list().get(0).processed();
		long ready = ready(queue);
		Thread.sleep(1000);

		assertThat(emitters.list().get(0).status()).isEqualTo(EmitterStatus.STOPPED);
		assertThat(simulated.list().get(0).status()).isEqualTo(Status.STOPPED);
		assertThat(ready(queue)).isEqualTo(ready);
		assertThat(simulated.list().get(0).processed()).isEqualTo(processed);
	}

	// ---- ayudas

	private static EmitterSpec spec(String name, RabbitMqDestination destination, Interval interval, String template) {
		return new EmitterSpec(name, destination, interval, template, null, null, false);
	}

	private long ready(String queue) {
		return queues.list().stream().filter(candidate -> candidate.name().equals(queue)).findFirst().orElseThrow().ready();
	}

	private String queue(String deadLetterQueue) {
		String name = "it-gen-q-" + UUID.randomUUID();
		queues.create(name, deadLetterQueue);
		createdQueues.add(0, name);
		return name;
	}

	private String exchange(ExchangeType type) {
		String name = "it-gen-ex-" + UUID.randomUUID();
		exchanges.create(name, type);
		createdExchanges.add(name);
		return name;
	}

	private static void ignoreMissing(Runnable deletion) {
		try {
			deletion.run();
		}
		catch (NotFoundException alreadyDeleted) {
			// la propia prueba ya lo eliminó
		}
	}

}
