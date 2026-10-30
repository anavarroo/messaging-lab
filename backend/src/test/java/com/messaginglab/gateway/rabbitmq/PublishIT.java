package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.PublishService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Publicación por AMQP contra un RabbitMQ real. */
class PublishIT {

	private static final AmqpConnectionProvider CONNECTIONS = new AmqpConnectionProvider(
			RabbitMqContainerSupport.properties());

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService bindings = new BindingService(topology);

	private final AmqpPublisher publisher = new AmqpPublisher(CONNECTIONS);

	private final PublishService service = new PublishService(topology, publisher);

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
	void aMessageWithABindingIsRoutedAndAnUnboundOneIsNot() {
		String queue = queue();
		String exchange = exchange(ExchangeType.DIRECT);
		bindings.create(exchange, queue, "alta");

		PublishResult routed = service.publish(exchange, "alta", "hola");
		PublishResult notRouted = service.publish(exchange, "otra", "hola");

		assertThat(routed.routed()).isTrue();
		assertThat(notRouted.routed()).isFalse();
		assertThat(queues.list().stream().filter(q -> q.name().equals(queue)).findFirst().orElseThrow().ready())
			.isEqualTo(1);
	}

	@Test
	void theMessageIsPersistentUtf8TextAndCanBeReadBack() throws Exception {
		String queue = queue();
		String exchange = exchange(ExchangeType.FANOUT);
		bindings.create(exchange, queue, null);
		String body = "<b>ñandú €</b> & \"comillas\" 😀\nsegunda línea";

		publisher.publish(new PublishRequest(exchange, "", body));

		String response = RabbitMqContainerSupport.managementPost("/api/queues/%2F/" + encode(queue) + "/get",
				"{\"count\":1,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}")
			.body();
		assertThat(response).contains("\"delivery_mode\":2")
			.contains("\"content_type\":\"text/plain\"")
			.contains("\"payload_encoding\":\"string\"");
		assertThat(RabbitMqContainerSupport.managementPost("/api/queues/%2F/" + encode(queue) + "/get",
				"{\"count\":1,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}")
			.body()).contains("segunda línea").contains("ñandú €").contains("😀");
	}

	@Test
	void publishingToTheDefaultExchangeReachesTheQueueNamedAsTheRoutingKey() {
		String queue = queue();

		assertThat(service.publish("", queue, "directo").routed()).isTrue();
		assertThat(service.publish("", "no-existe-" + UUID.randomUUID(), "directo").routed()).isFalse();
	}

	@Test
	void publishingToAnExchangeThatDisappearedIsNotFoundAndTheConnectionStillWorks() {
		String queue = queue();

		assertThatThrownBy(() -> publisher.publish(new PublishRequest("no-existe-" + UUID.randomUUID(), "a", "x")))
			.isInstanceOf(NotFoundException.class);

		assertThat(service.publish("", queue, "sigue funcionando").routed()).isTrue();
	}

	@Test
	void severalPublicationsInARowAreAllDelivered() {
		String queue = queue();

		for (int i = 0; i < 20; i++) {
			assertThat(service.publish("", queue, "mensaje " + i).routed()).isTrue();
		}

		assertThat(queues.list().stream().filter(q -> q.name().equals(queue)).findFirst().orElseThrow().ready())
			.isEqualTo(20);
	}

	@Test
	void aMessageOf64KbIsPublished() {
		String queue = queue();

		assertThat(service.publish("", queue, "x".repeat(64 * 1024)).routed()).isTrue();
	}

	private String exchange(ExchangeType type) {
		String name = "it-pub-ex-" + UUID.randomUUID();
		exchanges.create(name, type);
		createdExchanges.add(name);
		return name;
	}

	private String queue() {
		String name = "it-pub-q-" + UUID.randomUUID();
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

	private static String encode(String name) {
		return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
	}

}
