package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowNode;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.support.InMemoryBrokerTopology;
import com.messaginglab.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RabbitMqFlowTopologyTest {

	private static final String BASE = "http://rabbit.test:15672/api";

	private static final String CONNECTIONS = "[{\"name\":\"1.1.1.1:1 -> 2.2.2.2:5672\",\"client_properties\":{\"connection_name\":\"robot-ur\"}},"
			+ "{\"name\":\"1.1.1.1:2 -> 2.2.2.2:5672\",\"client_properties\":{}}]";

	private static final String CONSUMERS = "[{\"consumer_tag\":\"cocinero\",\"queue\":{\"name\":\"cocina\"},"
			+ "\"channel_details\":{\"connection_name\":\"1.1.1.1:1 -> 2.2.2.2:5672\",\"number\":1}},"
			+ "{\"consumer_tag\":\"amq.ctag-xyz\",\"queue\":{\"name\":\"cocina\"},"
			+ "\"channel_details\":{\"connection_name\":\"1.1.1.1:2 -> 2.2.2.2:5672\",\"number\":2}}]";

	private static final String ROBOT_CONSUMER = "consumer:1.1.1.1:1 -> 2.2.2.2:5672|1|cocina";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private MockRestServiceServer server;

	private RabbitMqFlowTopology topology;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		server.expect(manyTimes(), requestTo(BASE + "/connections"))
			.andRespond(withSuccess(CONNECTIONS, MediaType.APPLICATION_JSON));
		server.expect(manyTimes(), requestTo(BASE + "/consumers/%2F"))
			.andRespond(withSuccess(CONSUMERS, MediaType.APPLICATION_JSON));
		topology = new RabbitMqFlowTopology(broker,
				new ManagementApiClient(builder, new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "x", "/")), clock);
		broker.createExchange("pedidos", ExchangeType.TOPIC);
		broker.createQueue("cocina", null);
		broker.createBinding("pedidos", "cocina", "pedido.*");
	}

	private static FlowNode node(FlowTopology topology, String id) {
		return topology.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void drawsExchangesQueuesAndBindings() {
		FlowTopology result = topology.topology();

		assertThat(node(result, "router:pedidos")).extracting(FlowNode::kind, FlowNode::detail)
			.containsExactly(FlowNode.Kind.ROUTER, "topic");
		assertThat(node(result, "destination:cocina").kind()).isEqualTo(FlowNode.Kind.DESTINATION);
		assertThat(result.edges()).contains(new FlowTopology.Edge("router:pedidos", "destination:cocina", "pedido.*"));
	}

	@Test
	void doesNotDrawTheQueueTheVisualizerListensWith() {
		broker.createQueue(RabbitMqTraceSource.QUEUE_PREFIX + "abc", null);

		assertThat(topology.topology().nodes()).extracting(FlowNode::id).doesNotContain("destination:" + RabbitMqTraceSource.QUEUE_PREFIX + "abc");
		assertThat(topology.ready()).doesNotContainKey("destination:" + RabbitMqTraceSource.QUEUE_PREFIX + "abc");
	}

	@Test
	void alwaysOffersTheDefaultExchangeAsARouter() {
		assertThat(node(topology.topology(), "router:").label()).isEqualTo("(exchange por defecto)");
	}

	@Test
	void showsTheConnectedConsumersNamedAfterTheClientConnectionAndTag() {
		FlowTopology result = topology.topology();

		assertThat(node(result, ROBOT_CONSUMER)).extracting(FlowNode::kind, FlowNode::label)
			.containsExactly(FlowNode.Kind.CONSUMER, "robot-ur · cocinero");
		assertThat(result.edges()).contains(new FlowTopology.Edge("destination:cocina", ROBOT_CONSUMER, null));
	}

	@Test
	void anAutomaticConsumerTagIsReplacedByTheConnectionName() {
		FlowTopology result = topology.topology();

		assertThat(node(result, "consumer:1.1.1.1:2 -> 2.2.2.2:5672|2|cocina").label()).isEqualTo("1.1.1.1:2 -> 2.2.2.2:5672");
	}

	@Test
	void namesAnObservedPublisherAfterTheNameItsClientGaveItsConnection() {
		topology.observe(publication("producer:1.1.1.1:1 -> 2.2.2.2:5672"));

		assertThat(node(topology.topology(), "producer:1.1.1.1:1 -> 2.2.2.2:5672").label()).isEqualTo("robot-ur");
	}

	@Test
	void anObservedPublisherKeepsTheConnectionNameWhileManagementDoesNotKnowIt() {
		topology.observe(publication("producer:9.9.9.9:9 -> 2.2.2.2:5672"));

		assertThat(node(topology.topology(), "producer:9.9.9.9:9 -> 2.2.2.2:5672").label()).isEqualTo("9.9.9.9:9 -> 2.2.2.2:5672");
	}

	@Test
	void anObservedConsumerIsNamedAfterItsConnectionAndTagAndMatchesTheConnectedOne() {
		topology.observe(new FlowEvent(1, clock.instant(), FlowEvent.Type.DELIVER, null, "router:pedidos",
				List.of("destination:cocina"), ROBOT_CONSUMER, "k", 1, false, "x", false, Map.of()));

		FlowTopology result = topology.topology();

		assertThat(result.nodes()).filteredOn(node -> node.id().equals(ROBOT_CONSUMER)).hasSize(1);
		assertThat(node(result, ROBOT_CONSUMER).label()).isEqualTo("robot-ur · cocinero");
	}

	@Test
	void anObservedConsumerWithoutAConnectedMatchShowsItsConnection() {
		String basicGet = "consumer:1.1.1.1:1 -> 2.2.2.2:5672|9|cocina";
		topology.observe(new FlowEvent(1, clock.instant(), FlowEvent.Type.DELIVER, null, "router:pedidos",
				List.of("destination:cocina"), basicGet, "k", 1, false, "x", false, Map.of()));

		assertThat(node(topology.topology(), basicGet).label()).isEqualTo("robot-ur");
	}

	private FlowEvent publication(String producer) {
		return new FlowEvent(1, clock.instant(), FlowEvent.Type.PUBLISH, producer, "router:pedidos", List.of(), null, "k",
				1, false, "x", false, Map.of());
	}

	@Test
	void namesAPublisherThatIdentifiesItselfByItsAppId() {
		topology.observe(publication("producer:app:Brazo robótico"));

		assertThat(node(topology.topology(), "producer:app:Brazo robótico").label()).isEqualTo("Brazo robótico");
	}

	@Test
	void drawsObservedPublishersWithTheirConnectionToTheExchange() {
		topology.observe(new FlowEvent(1, clock.instant(), FlowEvent.Type.PUBLISH, "producer:robot-ur", "router:pedidos",
				List.of("destination:cocina"), null, "pedido.1", 1, false, "x", false, Map.of()));

		FlowTopology result = topology.topology();

		assertThat(node(result, "producer:robot-ur").kind()).isEqualTo(FlowNode.Kind.PRODUCER);
		assertThat(result.edges()).contains(new FlowTopology.Edge("producer:robot-ur", "router:pedidos", null));
	}

	@Test
	void forgetsAnObservedPublisherAfterFiveIdleMinutes() {
		topology.observe(new FlowEvent(1, clock.instant(), FlowEvent.Type.PUBLISH, "producer:robot-ur", "router:pedidos",
				List.of(), null, "k", 1, false, "x", false, Map.of()));
		clock.advance(Duration.ofMinutes(6));

		FlowTopology result = topology.topology();

		assertThat(result.nodes()).extracting(FlowNode::id).doesNotContain("producer:robot-ur");
		assertThat(result.edges()).noneMatch(edge -> edge.from().equals("producer:robot-ur"));
	}

	@Test
	void reportsTheReadyMessagesOfEachQueue() {
		broker.addReadyMessages("cocina", new com.messaginglab.model.QueueMessage("a", "pedidos", "k", false, null),
				new com.messaginglab.model.QueueMessage("b", "pedidos", "k", false, null));

		assertThat(topology.ready()).containsEntry("destination:cocina", 2L);
	}

	@Test
	void keepsTheLastKnownDetailsWhenManagementStopsAnswering() {
		topology.topology();
		server.reset();
		server.expect(manyTimes(), requestTo(BASE + "/connections")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		clock.advance(Duration.ofSeconds(10));

		FlowTopology result = topology.topology();

		assertThat(result.nodes()).extracting(FlowNode::id).contains(ROBOT_CONSUMER);
	}

}
