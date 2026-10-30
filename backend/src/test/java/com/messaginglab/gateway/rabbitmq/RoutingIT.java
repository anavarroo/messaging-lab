package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.PublishService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Enrutado de punta a punta (servicios, API de gestión y AMQP) comprobado con los contadores de las colas. */
class RoutingIT {

	private static final AmqpConnectionProvider CONNECTIONS = new AmqpConnectionProvider(
			RabbitMqContainerSupport.properties());

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService bindings = new BindingService(topology);

	private final PublishService publisher = new PublishService(topology, new AmqpPublisher(CONNECTIONS));

	private final List<String> createdExchanges = new ArrayList<>();

	private final List<String> createdQueues = new ArrayList<>();

	@AfterAll
	static void closeConnection() {
		CONNECTIONS.close();
	}

	@AfterEach
	void deleteWhatTheTestCreated() {
		createdExchanges.forEach(name -> ignoreMissing(() -> exchanges.delete(name)));
		createdQueues.forEach(name -> ignoreMissing(() -> queues.delete(name)));
	}

	@Test
	void directDeliversOnlyToTheQueueWithTheExactRoutingKey() {
		String exchange = exchange(ExchangeType.DIRECT);
		String altas = queue();
		String bajas = queue();
		String ambas = queue();
		bindings.create(exchange, altas, "alta");
		bindings.create(exchange, bajas, "baja");
		bindings.create(exchange, ambas, "alta");
		bindings.create(exchange, ambas, "baja");

		assertThat(publisher.publish(exchange, "alta", "m").routed()).isTrue();
		assertThat(publisher.publish(exchange, "Alta", "m").routed()).isFalse();
		assertThat(publisher.publish(exchange, "alta.x", "m").routed()).isFalse();
		assertThat(publisher.publish(exchange, "", "m").routed()).isFalse();

		assertThat(ready(altas)).isEqualTo(1);
		assertThat(ready(bajas)).isZero();
		assertThat(ready(ambas)).isEqualTo(1);
	}

	@Test
	void directWithAnEmptyRoutingKeyBindingMatchesOnlyTheEmptyKey() {
		String exchange = exchange(ExchangeType.DIRECT);
		String queue = queue();
		bindings.create(exchange, queue, "");

		assertThat(publisher.publish(exchange, "", "m").routed()).isTrue();
		assertThat(publisher.publish(exchange, "algo", "m").routed()).isFalse();

		assertThat(ready(queue)).isEqualTo(1);
	}

	@Test
	void fanoutDeliversToAllItsQueuesWhateverTheRoutingKey() {
		String exchange = exchange(ExchangeType.FANOUT);
		String first = queue();
		String second = queue();
		String unbound = queue();
		bindings.create(exchange, first, null);
		bindings.create(exchange, second, null);

		assertThat(publisher.publish(exchange, "", "m").routed()).isTrue();
		assertThat(publisher.publish(exchange, "lo-que-sea", "m").routed()).isTrue();

		assertThat(ready(first)).isEqualTo(2);
		assertThat(ready(second)).isEqualTo(2);
		assertThat(ready(unbound)).isZero();
	}

	@Test
	void aFanoutWithoutQueuesDoesNotRoute() {
		assertThat(publisher.publish(exchange(ExchangeType.FANOUT), "", "m").routed()).isFalse();
	}

	@Test
	void theDefaultExchangeDeliversToTheQueueNamedAsTheRoutingKey() {
		String target = queue();
		String other = queue();

		assertThat(publisher.publish("", target, "m").routed()).isTrue();

		assertThat(ready(target)).isEqualTo(1);
		assertThat(ready(other)).isZero();
	}

	@Test
	void theDefaultExchangeWithAQueueNameThatDoesNotExistIsNotRouted() {
		assertThat(publisher.publish("", "no-existe-" + UUID.randomUUID(), "m").routed()).isFalse();
	}

	@Test
	void theMessagesKeepTheirOrderInTheQueue() throws Exception {
		String target = queue();

		for (int i = 0; i < 5; i++) {
			publisher.publish("", target, "mensaje-" + i);
		}

		String body = RabbitMqContainerSupport.managementPost("/api/queues/%2F/" + target + "/get",
				"{\"count\":5,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}")
			.body();
		assertThat(body.indexOf("mensaje-0")).isLessThan(body.indexOf("mensaje-1"));
		assertThat(body.indexOf("mensaje-3")).isLessThan(body.indexOf("mensaje-4"));
	}

	@ParameterizedTest(name = "patrón «{0}» con la routing key «{1}» -> {2}")
	@MethodSource("topicCases")
	void topicMatchesWildcardsAsDocumented(String pattern, String routingKey, boolean delivered) {
		String exchange = exchange(ExchangeType.TOPIC);
		String queue = queue();
		bindings.create(exchange, queue, pattern);

		boolean routed = publisher.publish(exchange, routingKey, "m").routed();

		assertThat(routed).isEqualTo(delivered);
		assertThat(ready(queue)).isEqualTo(delivered ? 1 : 0);
	}

	static Stream<Arguments> topicCases() {
		return Stream.of(
				// «*» sustituye exactamente una palabra
				Arguments.of("a.*", "a.b", true), Arguments.of("a.*", "a", false), Arguments.of("a.*", "a.b.c", false),
				Arguments.of("a.*", "b.a", false), Arguments.of("a.*", "", false),
				// «#» sustituye cero o más palabras
				Arguments.of("a.#", "a", true), Arguments.of("a.#", "a.b", true), Arguments.of("a.#", "a.b.c", true),
				Arguments.of("a.#", "b.a", false), Arguments.of("a.#", "ab", false),
				// «#» solo lo recibe todo, también la routing key vacía
				Arguments.of("#", "", true), Arguments.of("#", "a", true), Arguments.of("#", "a.b.c.d", true),
				// «*» solo es exactamente una palabra
				Arguments.of("*", "a", true), Arguments.of("*", "a.b", false), Arguments.of("*", "", false),
				// comodines en medio
				Arguments.of("*.b.*", "x.b.y", true), Arguments.of("*.b.*", "b.y", false),
				Arguments.of("*.b.*", "x.b", false), Arguments.of("*.b.*", "x.b.y.z", false),
				Arguments.of("*.b.*", "x.c.y", false),
				// sin comodines se comporta como un direct
				Arguments.of("a.b", "a.b", true), Arguments.of("a.b", "a.b.c", false),
				Arguments.of("a.#.z", "a.z", true), Arguments.of("a.#.z", "a.b.c.z", true),
				Arguments.of("a.#.z", "a.b", false));
	}

	@Test
	void aTopicDeliversToEveryQueueWhosePatternMatches() {
		String exchange = exchange(ExchangeType.TOPIC);
		String specific = queue();
		String family = queue();
		String everything = queue();
		String unrelated = queue();
		bindings.create(exchange, specific, "pedido.creado");
		bindings.create(exchange, family, "pedido.*");
		bindings.create(exchange, everything, "#");
		bindings.create(exchange, unrelated, "factura.*");

		assertThat(publisher.publish(exchange, "pedido.creado", "m").routed()).isTrue();
		assertThat(publisher.publish(exchange, "pedido.pagado", "m").routed()).isTrue();

		assertThat(ready(specific)).isEqualTo(1);
		assertThat(ready(family)).isEqualTo(2);
		assertThat(ready(everything)).isEqualTo(2);
		assertThat(ready(unrelated)).isZero();
	}

	@Test
	void aQueueWithTwoMatchingBindingsOfTheSameExchangeGetsTheMessageOnce() {
		String exchange = exchange(ExchangeType.TOPIC);
		String queue = queue();
		bindings.create(exchange, queue, "a.*");
		bindings.create(exchange, queue, "a.#");

		publisher.publish(exchange, "a.b", "m");

		assertThat(ready(queue)).isEqualTo(1);
	}

	private long ready(String queue) {
		return queues.list().stream().filter(candidate -> candidate.name().equals(queue)).map(Queue::ready).findFirst()
			.orElseThrow();
	}

	private String exchange(ExchangeType type) {
		String name = "it-route-ex-" + UUID.randomUUID();
		exchanges.create(name, type);
		createdExchanges.add(name);
		return name;
	}

	private String queue() {
		String name = "it-route-q-" + UUID.randomUUID();
		queues.create(name, null);
		createdQueues.add(name);
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
