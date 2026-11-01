package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowMessage;
import com.messaginglab.flow.FlowService;
import com.messaginglab.flow.FlowService.Subscription;
import com.messaginglab.flow.FlowSourceStatus;
import com.messaginglab.flow.FlowTick;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.messaginglab.support.RecordingConsumerListener;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** El visualizador de flujo contra un RabbitMQ real con el trazado activado. */
class FlowIT {

	private static final RabbitMqProperties PROPERTIES = RabbitMqContainerSupport.properties();

	private static final AmqpConnectionProvider LAB_CONNECTION = new AmqpConnectionProvider(PROPERTIES);

	private final ManagementApiClient management = new ManagementApiClient(PROPERTIES);

	private final RabbitMqTopology broker = new RabbitMqTopology(management);

	private final RabbitMqFlowTopology topology = new RabbitMqFlowTopology(broker, management, Clock.systemUTC());

	private final RabbitMqTraceSource source = new RabbitMqTraceSource(PROPERTIES, management, topology,
			Clock.systemUTC());

	private final FlowService service = new FlowService(source, topology, Clock.systemUTC());

	private final String exchange = "it-flow-ex-" + UUID.randomUUID();

	private final String queue = "it-flow-q-" + UUID.randomUUID();

	private final List<AutoCloseable> toClose = new ArrayList<>();

	private final List<FlowMessage> received = new ArrayList<>();

	private Subscription screen;

	private Connection publisher;

	private Channel channel;

	@BeforeEach
	void setUp() throws Exception {
		RabbitMqContainerSupport.rabbitmqctl("trace_on");
		broker.createExchange(exchange, ExchangeType.DIRECT);
		broker.createQueue(queue, null);
		broker.createBinding(exchange, queue, "ruta");
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(RabbitMqContainerSupport.host());
		factory.setPort(RabbitMqContainerSupport.amqpPort());
		publisher = factory.newConnection("it-publisher");
		channel = publisher.createChannel();
		screen = service.subscribe();
		await().until(() -> source.status().state() == FlowSourceStatus.State.ACTIVE);
	}

	@AfterEach
	void tearDown() throws Exception {
		screen.close();
		for (AutoCloseable closeable : toClose) {
			closeable.close();
		}
		publisher.close();
		RabbitMqContainerSupport.rabbitmqctl("trace_on");
		broker.deleteExchange(exchange);
		broker.deleteQueue(queue);
	}

	/** Nombre que muestra el diagrama para un nodo; RabbitMQ tarda unos segundos en conocer las conexiones nuevas. */
	private String labelOf(String nodeId) {
		return topology.topology().nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst().orElseThrow().label();
	}

	private void publish(String key, String body) throws Exception {
		channel.basicPublish(exchange, key, null, body.getBytes());
	}

	/** Eventos recibidos hasta ahora, acumulando lo que llega a la pantalla. */
	private List<FlowEvent> events() {
		screen.pending().forEach(received::add);
		return received.stream().filter(FlowMessage.Event.class::isInstance).map(m -> ((FlowMessage.Event) m).event()).toList();
	}

	private FlowEvent awaitEvent(FlowEvent.Type type, String key) {
		await().atMost(Duration.ofSeconds(15)).until(() -> events().stream().anyMatch(e -> e.type() == type && e.key().equals(key)));
		return events().stream().filter(e -> e.type() == type && e.key().equals(key)).findFirst().orElseThrow();
	}

	@Test
	void aRoutedPublicationShowsItsExchangeQueueAndPublisher() throws Exception {
		publish("ruta", "{\"t\":1}");

		FlowEvent event = awaitEvent(FlowEvent.Type.PUBLISH, "ruta");

		assertThat(event.router()).isEqualTo("router:" + exchange);
		assertThat(event.destinations()).containsExactly("destination:" + queue);
		assertThat(event.producer()).startsWith("producer:");
		assertThat(event.body()).isEqualTo("{\"t\":1}");
		assertThat(event.unrouted()).isFalse();
	}

	@Test
	void aPublicationWithoutADestinationIsMarkedAsUnrouted() throws Exception {
		publish("sin-destino", "x");

		FlowEvent event = awaitEvent(FlowEvent.Type.PUBLISH, "sin-destino");

		assertThat(event.unrouted()).isTrue();
		assertThat(event.destinations()).isEmpty();
	}

	@Test
	void aDeliveryToAClientConsumerNamesTheConsumerByItsConnectionAndTag() throws Exception {
		Connection consumerConnection = new ConnectionFactoryBuilder().connect("it-consumer");
		toClose.add(consumerConnection);
		Channel consumerChannel = consumerConnection.createChannel();
		consumerChannel.basicConsume(queue, true, "mi-consumidor", (tag, delivery) -> { }, tag -> { });
		publish("ruta", "para-el-consumidor");

		FlowEvent delivery = awaitEvent(FlowEvent.Type.DELIVER, "ruta");

		assertThat(delivery.destinations()).containsExactly("destination:" + queue);
		assertThat(delivery.consumer()).startsWith("consumer:").endsWith("|1|" + queue);
		await().atMost(Duration.ofSeconds(20)).until(() -> labelOf(delivery.consumer()).equals("it-consumer · mi-consumidor"));
		assertThat(delivery.redelivered()).isFalse();
	}

	@Test
	void aDeliveryToALabConsumerIsIdentifiedByTheLabConnection() throws Exception {
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = new AmqpConsumers(LAB_CONNECTION).start(queue, AckMode.AUTOMATIC, listener);
		toClose.add(handle::stop);
		publish("ruta", "para-el-laboratorio");

		FlowEvent delivery = awaitEvent(FlowEvent.Type.DELIVER, "ruta");

		assertThat(listener.next().message().body()).isEqualTo("para-el-laboratorio");
		await().atMost(Duration.ofSeconds(20)).until(() -> labelOf(delivery.consumer()).startsWith("messaging-lab"));
	}

	@Test
	void anEmitterAndASimulatedConsumerOfTheLabAppearUnderTheirOwnNames() throws Exception {
		new AmqpPublisher(LAB_CONNECTION).publish(new PublishRequest(exchange, "ruta", "x", "Brazo robótico"));
		ConsumerHandle handle = new AmqpConsumers(LAB_CONNECTION).start(queue, AckMode.AUTOMATIC, new RecordingConsumerListener(),
				"cocinero");
		toClose.add(handle::stop);

		FlowEvent publication = awaitEvent(FlowEvent.Type.PUBLISH, "ruta");
		assertThat(publication.producer()).isEqualTo("producer:app:Brazo robótico");
		assertThat(publication.properties()).containsEntry("app_id", "Brazo robótico");
		FlowEvent delivery = awaitEvent(FlowEvent.Type.DELIVER, "ruta");

		assertThat(labelOf(publication.producer())).isEqualTo("Brazo robótico");
		await().atMost(Duration.ofSeconds(20)).until(() -> labelOf(delivery.consumer()).equals("cocinero"));
	}

	@Test
	void theVisualizerDoesNotShowItsOwnTraffic() throws Exception {
		publish("ruta", "uno");
		awaitEvent(FlowEvent.Type.PUBLISH, "ruta");
		Thread.sleep(1_500);

		assertThat(events()).noneMatch(event -> event.destinations().stream().anyMatch(d -> d.contains(RabbitMqTraceSource.QUEUE_PREFIX)));
	}

	@Test
	void theTopologyShowsTheExchangeTheQueueTheBindingAndTheObservedPublisher() throws Exception {
		publish("ruta", "uno");
		String producer = awaitEvent(FlowEvent.Type.PUBLISH, "ruta").producer();

		FlowTopology result = topology.topology();

		assertThat(result.nodes()).extracting(n -> n.id()).contains("router:" + exchange, "destination:" + queue);
		assertThat(result.edges()).contains(new FlowTopology.Edge("router:" + exchange, "destination:" + queue, "ruta"),
				new FlowTopology.Edge(producer, "router:" + exchange, null));
		await().atMost(Duration.ofSeconds(20)).until(() -> labelOf(producer).equals("it-publisher"));
	}

	@Test
	void theTickCountsTheRatesAndTheReadyMessages() throws Exception {
		for (int i = 0; i < 5; i++) {
			publish("ruta", "m" + i);
		}
		await().until(() -> events().stream().filter(e -> e.type() == FlowEvent.Type.PUBLISH).count() >= 5);
		await().until(() -> topology.ready().getOrDefault("destination:" + queue, 0L) == 5L);

		service.tick();
		events();

		FlowTick tick = received.stream()
			.filter(FlowMessage.Tick.class::isInstance)
			.map(message -> ((FlowMessage.Tick) message).tick())
			.reduce((first, second) -> second)
			.orElseThrow();
		assertThat(tick.ready()).containsEntry("destination:" + queue, 5L);
		assertThat(tick.published()).containsKey("router:" + exchange).containsKey("destination:" + queue);
	}

	@Test
	void withoutTracingTheStateExplainsHowToEnableItAndEventsResumeWhenItIsBack() throws Exception {
		RabbitMqContainerSupport.rabbitmqctl("trace_off");

		await().until(() -> source.status().state() == FlowSourceStatus.State.DISABLED);
		assertThat(source.status().hint()).contains("rabbitmqctl trace_on");
		service.tick();
		assertThat(screen.pending()).anySatisfy(message -> assertThat(message)
			.isInstanceOfSatisfying(FlowMessage.Status.class, s -> assertThat(s.status().state()).isEqualTo(FlowSourceStatus.State.DISABLED)));

		RabbitMqContainerSupport.rabbitmqctl("trace_on");
		await().until(() -> source.status().state() == FlowSourceStatus.State.ACTIVE);
		service.tick();
		publish("ruta", "despues");

		awaitEvent(FlowEvent.Type.PUBLISH, "ruta");
	}

	/** Conexión AMQP del cliente de la prueba con el nombre que se indique. */
	private static final class ConnectionFactoryBuilder {

		Connection connect(String name) throws Exception {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(RabbitMqContainerSupport.host());
			factory.setPort(RabbitMqContainerSupport.amqpPort());
			return factory.newConnection(name);
		}

	}

}
