package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.messaginglab.assistant.Snapshot;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowSource;
import com.messaginglab.flow.FlowSourceStatus;
import com.messaginglab.flow.FlowService;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.flow.FlowTopologyProvider;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.EmitterSpec;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.Interval;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec.FailureAction;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.FakeMessagePublisher;
import com.messaginglab.support.FakeMessagePublisher.TestDestination;
import com.messaginglab.support.InMemoryBrokerTopology;
import com.messaginglab.support.ManualScheduler;
import com.messaginglab.support.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RabbitMqSnapshotProviderTest {

	private static final String BASE = "http://rabbit.test:15672/api";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private final FakeMessagePublisher publisher = new FakeMessagePublisher();

	private final EmitterService emitters = new EmitterService(publisher, scheduler, clock, () -> new Random(1), new GeneratorLimits());

	private final SimulatedConsumerService consumers = new SimulatedConsumerService(new FakeBrokerConsumers(), scheduler, clock,
			() -> new Random(1));

	private FlowSourceStatus traceStatus = new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null);

	private final FlowService flow = new FlowService(new FlowSource() {
		@Override
		public FlowSourceStatus status() {
			return traceStatus;
		}

		@Override
		public void start(Consumer<FlowEvent> sink) {
		}

		@Override
		public void stop() {
		}
	}, new FlowTopologyProvider() {
		@Override
		public FlowTopology topology() {
			return FlowTopology.EMPTY;
		}

		@Override
		public Map<String, Long> ready() {
			return Map.of();
		}
	}, clock);

	private MockRestServiceServer server;

	private RabbitMqSnapshotProvider provider;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		provider = new RabbitMqSnapshotProvider(broker, new ManagementApiClient(builder,
				new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "x", "/")), emitters, consumers, flow, clock);
	}

	@AfterEach
	void tearDown() {
		emitters.shutdown();
		consumers.shutdown();
	}

	private void stats(String json) {
		server.expect(manyTimes(), requestTo(BASE + "/queues/%2F")).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	private static final String COCINA_STATS = "[{\"name\":\"cocina\",\"consumers\":1,\"message_stats\":{\"publish_details\":{\"rate\":11.0},"
			+ "\"deliver_get_details\":{\"rate\":4.8}}},{\"name\":\"barra\",\"consumers\":0},"
			+ "{\"name\":\"fallidos\",\"consumers\":0,\"message_stats\":{\"publish_details\":{\"rate\":0}}}]";

	@Test
	void describesTheQueuesWithTheirConsumersAndRealRates() {
		broker.createQueue("fallidos", null);
		broker.createQueue("cocina", "fallidos");
		broker.createQueue("barra", null);
		broker.addReadyMessages("cocina", message(), message());
		stats(COCINA_STATS);

		Snapshot snapshot = provider.current();

		assertThat(snapshot.broker().available()).isTrue();
		Snapshot.Queue cocina = queue(snapshot, "cocina");
		assertThat(cocina.ready()).isEqualTo(2);
		assertThat(cocina.consumers()).isEqualTo(1);
		assertThat(cocina.inPerSecond()).isEqualTo(11.0);
		assertThat(cocina.outPerSecond()).isEqualTo(4.8);
		assertThat(cocina.deadLetterQueue()).isEqualTo("fallidos");
		assertThat(queue(snapshot, "barra").consumers()).isZero();
	}

	@Test
	void aQueueWithoutAnyTrafficYetHasZeroRatesNotUnknownOnes() {
		broker.createQueue("barra", null);
		stats(COCINA_STATS);

		Snapshot.Queue barra = queue(provider.current(), "barra");

		assertThat(barra.inPerSecond()).isZero();
		assertThat(barra.outPerSecond()).isZero();
	}

	@Test
	void withoutManagementStatisticsTheConsumersAndRatesAreUnknownNotZero() {
		broker.createQueue("cocina", null);
		server.expect(manyTimes(), requestTo(BASE + "/queues/%2F")).andRespond(withServerError());

		Snapshot.Queue cocina = queue(provider.current(), "cocina");

		assertThat(cocina.consumers()).isEqualTo(-1);
		assertThat(cocina.inPerSecond()).isNull();
		assertThat(cocina.outPerSecond()).isNull();
	}

	@Test
	void leavesOutTheQueueTheVisualizerListensWith() {
		broker.createQueue(RabbitMqTraceSource.QUEUE_PREFIX + "abc", null);
		broker.createQueue("cocina", null);
		stats("[]");

		assertThat(provider.current().queues()).extracting(Snapshot.Queue::name).containsExactly("cocina");
	}

	@Test
	void describesTheExchangesWithTheQueuesTheyAreBoundTo() {
		broker.createQueue("cocina", null);
		broker.createQueue("barra", null);
		broker.createExchange("pizzeria", ExchangeType.TOPIC);
		broker.createExchange("avisos", ExchangeType.FANOUT);
		broker.createBinding("pizzeria", "cocina", "pedido.pizza");
		broker.createBinding("pizzeria", "cocina", "pedido.otra");
		broker.createBinding("pizzeria", "barra", "pedido.bebida");
		stats("[]");

		List<Snapshot.Exchange> exchanges = provider.current().exchanges();

		assertThat(exchanges).extracting(Snapshot.Exchange::name).contains("pizzeria", "avisos").doesNotContain("amq.direct", "amq.topic");
		Snapshot.Exchange pizzeria = exchanges.stream().filter(e -> e.name().equals("pizzeria")).findFirst().orElseThrow();
		assertThat(pizzeria.type()).isEqualTo("topic");
		assertThat(pizzeria.boundQueues()).containsExactlyInAnyOrder("cocina", "barra");
		assertThat(exchanges.stream().filter(e -> e.name().equals("avisos")).findFirst().orElseThrow().boundQueues()).isEmpty();
	}

	@Test
	void describesTheEmittersTheSimulatedConsumersAndTheLoad() {
		stats("[]");
		var emitter = emitters.create(new EmitterSpec("Brazo", new TestDestination("cocina"), Interval.fixed(100), "{}", null, null, false));
		emitters.start(emitter.id());
		var consumer = consumers.create(new SimulatedConsumerSpec("lector", "cocina", Interval.fixed(200), 0, FailureAction.REQUEUE));

		Snapshot snapshot = provider.current();

		assertThat(snapshot.emitters()).singleElement().satisfies(e -> {
			assertThat(e.name()).isEqualTo("Brazo");
			assertThat(e.status()).isEqualTo(Snapshot.Run.RUNNING);
			assertThat(e.destination()).isEqualTo("el destino «cocina»");
		});
		assertThat(snapshot.consumers()).singleElement().satisfies(c -> {
			assertThat(c.name()).isEqualTo("lector");
			assertThat(c.queue()).isEqualTo("cocina");
			assertThat(c.status()).isEqualTo(Snapshot.Run.STOPPED);
		});
		assertThat(snapshot.load().maxRatePerSecond()).isEqualTo(100);
		assertThat(snapshot.load().ratePerSecondInUse()).isEqualTo(10.0);
		assertThat(consumer.id()).isNotBlank();
	}

	@Test
	void translatesTheStateOfTheTrace() {
		stats("[]");
		assertThat(provider.current().trace().state()).isEqualTo(Snapshot.Trace.State.ACTIVE);

		traceStatus = new FlowSourceStatus(FlowSourceStatus.State.DISABLED, "Ejecuta trace_on");
		Snapshot.Trace disabled = provider.current().trace();
		assertThat(disabled.state()).isEqualTo(Snapshot.Trace.State.DISABLED);
		assertThat(disabled.hint()).isEqualTo("Ejecuta trace_on");

		traceStatus = new FlowSourceStatus(FlowSourceStatus.State.UNAVAILABLE, "caído");
		assertThat(provider.current().trace().state()).isEqualTo(Snapshot.Trace.State.UNKNOWN);
	}

	@Test
	void whenRabbitMqIsDownThePhotoSaysSoInsteadOfFailingAndKeepsTheGenerator() {
		InMemoryBrokerTopology down = new InMemoryBrokerTopology() {
			@Override
			public com.messaginglab.model.BrokerStatus status() {
				throw new BrokerUnavailableException("RabbitMQ no está disponible.");
			}
		};
		emitters.create(new EmitterSpec("Brazo", new TestDestination("x"), Interval.fixed(100), "{}", null, null, false));
		RabbitMqSnapshotProvider offline = new RabbitMqSnapshotProvider(down, new ManagementApiClient(RestClient.builder(),
				new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "x", "/")), emitters, consumers, flow, clock);

		Snapshot snapshot = offline.current();

		assertThat(snapshot.broker().available()).isFalse();
		assertThat(snapshot.broker().reason()).isEqualTo("RabbitMQ no está disponible.");
		assertThat(snapshot.queues()).isEmpty();
		assertThat(snapshot.trace().state()).isEqualTo(Snapshot.Trace.State.UNKNOWN);
		assertThat(snapshot.emitters()).hasSize(1);
	}

	@Test
	void whenRabbitMqStopsAnsweringHalfwayThePhotoAlsoSaysSo() {
		InMemoryBrokerTopology flaky = new InMemoryBrokerTopology() {
			@Override
			public List<Queue> listQueues() {
				throw new BrokerUnavailableException("RabbitMQ no está disponible.");
			}
		};
		stats("[]");
		RabbitMqSnapshotProvider half = new RabbitMqSnapshotProvider(flaky, new ManagementApiClient(RestClient.builder(),
				new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "x", "/")), emitters, consumers, flow, clock);

		assertThat(half.current().broker().available()).isFalse();
	}

	@Test
	void usesTheClockForTheTimeOfThePhoto() {
		stats("[]");

		assertThat(provider.current().time()).isEqualTo(Instant.parse("2026-10-31T10:00:00Z"));
		assertThat(HttpStatus.OK).isNotNull();
	}

	private static Snapshot.Queue queue(Snapshot snapshot, String name) {
		return snapshot.queues().stream().filter(q -> q.name().equals(name)).findFirst().orElseThrow();
	}

	private static com.messaginglab.model.QueueMessage message() {
		return new com.messaginglab.model.QueueMessage("x", "ex", "k", false, null);
	}

}
