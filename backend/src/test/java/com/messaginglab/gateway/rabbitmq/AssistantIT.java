package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.messaginglab.assistant.AssistantService;
import com.messaginglab.assistant.Cancellation;
import com.messaginglab.assistant.ContextBuilder;
import com.messaginglab.assistant.KnowledgeBase;
import com.messaginglab.assistant.LlmClient;
import com.messaginglab.assistant.LlmStatus;
import com.messaginglab.assistant.Message;
import com.messaginglab.assistant.QuestionClassifier;
import com.messaginglab.assistant.Snapshot;
import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowService;
import com.messaginglab.flow.FlowSource;
import com.messaginglab.flow.FlowSourceStatus;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.flow.FlowTopologyProvider;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.FakeMessagePublisher;
import com.messaginglab.support.ManualScheduler;
import com.messaginglab.support.MutableClock;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.messaginglab.support.RecordingConsumerListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El asistente contra un RabbitMQ real: la foto sale de RabbitMQ de verdad y lo que recibiría el modelo (que aquí es
 * un modelo falso que guarda el mensaje) refleja el estado real.
 */
class AssistantIT {

	private static final AmqpConnectionProvider CONNECTION = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());

	private final ManagementApiClient management = new ManagementApiClient(RabbitMqContainerSupport.properties());

	private final RabbitMqTopology broker = new RabbitMqTopology(management);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final EmitterService emitters = new EmitterService(new FakeMessagePublisher(), scheduler, clock, () -> new Random(1),
			new GeneratorLimits());

	private final SimulatedConsumerService simulated = new SimulatedConsumerService(new FakeBrokerConsumers(), scheduler, clock,
			() -> new Random(1));

	private final FlowService flow = new FlowService(new FlowSource() {
		@Override
		public FlowSourceStatus status() {
			return new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null);
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
	}, Clock.systemUTC());

	private final RabbitMqSnapshotProvider provider = new RabbitMqSnapshotProvider(broker, management, emitters, simulated, flow,
			Clock.systemUTC());

	private final List<String> sent = new ArrayList<>();

	private final LlmClient llm = new LlmClient() {
		@Override
		public LlmStatus status() {
			return LlmStatus.ready();
		}

		@Override
		public void chat(List<Message> messages, Consumer<String> onToken, Cancellation cancel) {
			sent.add(messages.get(messages.size() - 1).text());
			onToken.accept("ok");
		}
	};

	private final AssistantService assistant = new AssistantService(provider, llm, new QuestionClassifier(),
			new ContextBuilder(new KnowledgeBase()), Clock.systemUTC());

	private final String suffix = UUID.randomUUID().toString().substring(0, 8);

	private final List<String> queues = new ArrayList<>();

	private final List<ConsumerHandle> handles = new ArrayList<>();

	private final List<String> exchanges = new ArrayList<>();

	private final AmqpPublisher publisher = new AmqpPublisher(CONNECTION);

	@BeforeEach
	void setUp() {
		// la API de gestión tarda unos segundos en reflejar las estadísticas: se espera a lo que cada prueba necesita
	}

	@AfterEach
	void tearDown() {
		handles.forEach(ConsumerHandle::stop);
		queues.forEach(name -> ignore(() -> broker.deleteQueue(name)));
		exchanges.forEach(name -> ignore(() -> broker.deleteExchange(name)));
		emitters.shutdown();
		simulated.shutdown();
	}

	private static void ignore(Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException exception) {
			// ya no existe
		}
	}

	private String queue(String base) {
		String name = "it-asistente-" + base + "-" + suffix;
		broker.createQueue(name, null);
		queues.add(name);
		return name;
	}

	private void publish(String queue, int count) {
		for (int i = 0; i < count; i++) {
			publisher.publish(new PublishRequest("", queue, "m" + i));
		}
	}

	private String ask(String question) {
		sent.clear();
		assistant.ask(question, List.of(), token -> { }, new Cancellation());
		return sent.get(0);
	}

	@Test
	void aQueueWithMessagesAndNoConsumerIsExplainedAsSuch() {
		String name = queue("sin-consumidor");
		publish(name, 20);
		await().atMost(Duration.ofSeconds(20)).until(() -> provider.current().queues().stream()
			.anyMatch(q -> q.name().equals(name) && q.ready() == 20 && q.consumers() == 0));

		String prompt = ask("¿Por qué no se vacía la cola " + name + "?");

		assertThat(prompt).contains("CAUSA: la cola «" + name + "» no se vacía porque NO TIENE NINGÚN CONSUMIDOR")
			.contains("Tiene 20 mensajes listos").contains("- cola «" + name + "»: 20 listos");
	}

	@Test
	void aQueueWithAConsumerIsNotAccusedOfHavingNone() {
		String name = queue("con-consumidor");
		handles.add(new AmqpConsumers(CONNECTION).start(name, AckMode.MANUAL, new RecordingConsumerListener()));
		publish(name, 3);
		await().atMost(Duration.ofSeconds(20)).until(() -> provider.current().queues().stream()
			.anyMatch(q -> q.name().equals(name) && q.consumers() == 1));

		String prompt = ask("¿Cómo va la cola " + name + "?");

		assertThat(prompt).doesNotContain("NO TIENE NINGÚN CONSUMIDOR").contains("1 consumidor");
	}

	@Test
	void aDeadLetterQueueWithMessagesNamesTheQueueThatSentThemThere() {
		String dlq = queue("dlq");
		String origin = "it-asistente-origen-" + suffix;
		broker.createQueue(origin, dlq);
		queues.add(origin);
		publish(dlq, 5);
		await().atMost(Duration.ofSeconds(20)).until(() -> provider.current().queues().stream()
			.anyMatch(q -> q.name().equals(dlq) && q.ready() == 5));

		String prompt = ask("¿Qué hay en la dead-letter queue " + dlq + "?");

		assertThat(prompt).contains("La dead-letter queue «" + dlq + "» tiene 5 mensajes listos").contains("«" + origin + "» rechazó sin reencolar");
	}

	@Test
	void theRealTrafficOfAQueueShowsUpAsARate() {
		String name = queue("ritmo");
		handles.add(new AmqpConsumers(CONNECTION).start(name, AckMode.AUTOMATIC, new RecordingConsumerListener()));
		long end = System.currentTimeMillis() + 12_000;

		await().atMost(Duration.ofSeconds(40)).until(() -> {
			publish(name, 5);
			Thread.sleep(250);
			return provider.current().queues().stream().anyMatch(q -> q.name().equals(name) && q.inPerSecond() != null && q.inPerSecond() > 0)
					|| System.currentTimeMillis() > end + 20_000;
		});

		Snapshot.Queue queue = provider.current().queues().stream().filter(q -> q.name().equals(name)).findFirst().orElseThrow();
		assertThat(queue.inPerSecond()).isGreaterThan(0);
	}

	@Test
	void theExchangesAndTheirBindingsAreDescribed() {
		String name = queue("enlazada");
		String exchange = "it-asistente-ex-" + suffix;
		broker.createExchange(exchange, ExchangeType.TOPIC);
		exchanges.add(exchange);
		broker.createBinding(exchange, name, "a.*");

		String prompt = ask("¿A qué colas va el exchange " + exchange + "?");

		assertThat(prompt).contains("- exchange «" + exchange + "» (topic): enlazado a «" + name + "»");
	}

	@Test
	void theTraceStateAndTheBrokerVersionAreInTheStateAnswer() {
		String prompt = ask("¿Funciona RabbitMQ?");

		assertThat(prompt).containsPattern("RabbitMQ: levantado \\(versión \\d+\\.\\d+");
	}

}
