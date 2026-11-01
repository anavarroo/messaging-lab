package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.PublishService;
import com.messaginglab.service.QueueInspectionService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contadores, inspección y vaciado contra un RabbitMQ real. */
class InspectionIT {

	private static final AmqpConnectionProvider CONNECTIONS = new AmqpConnectionProvider(
			RabbitMqContainerSupport.properties());

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService bindings = new BindingService(topology);

	private final PublishService publisher = new PublishService(topology, new AmqpPublisher(CONNECTIONS));

	private final QueueInspectionService inspector = new QueueInspectionService(topology);

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
	void theCountersReflectAPublicationImmediately() {
		String queue = queue(null);
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 0, 0));

		publisher.publish("", queue, "uno");
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 1, 0, 1));

		publisher.publish("", queue, "dos");
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 2, 0, 2));
	}

	@Test
	void inspectingShowsTheMessagesInOrderAndDoesNotRemoveThem() {
		String queue = queue(null);
		String exchange = exchange();
		bindings.create(exchange, queue, "rk");
		publisher.publish(exchange, "rk", "primero <b>ñ</b>");
		publisher.publish(exchange, "rk", "segundo");
		publisher.publish(exchange, "rk", "tercero");

		QueueInspection inspection = inspector.inspect(queue);

		assertThat(inspection.messages()).extracting(QueueMessage::body)
			.containsExactly("primero <b>ñ</b>", "segundo", "tercero");
		assertThat(inspection.messages()).allMatch(message -> message.exchange().equals(exchange)
			&& message.routingKey().equals("rk") && message.deathInfo() == null);
		assertThat(inspection.total()).isEqualTo(3);
		assertThat(inspection.truncated()).isFalse();
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 3, 0, 3));
	}

	@Test
	void aSecondInspectionReturnsTheMessagesMarkedAsRedelivered() {
		String queue = queue(null);
		publisher.publish("", queue, "uno");

		QueueInspection first = inspector.inspect(queue);
		QueueInspection second = inspector.inspect(queue);

		assertThat(first.messages().get(0).redelivered()).isFalse();
		assertThat(second.messages().get(0).redelivered()).isTrue();
		assertThat(second.messages().get(0).body()).isEqualTo("uno");
		assertThat(counters(queue).ready()).isEqualTo(1);
	}

	@Test
	void anEmptyQueueHasNoMessages() {
		QueueInspection inspection = inspector.inspect(queue(null));

		assertThat(inspection).isEqualTo(new QueueInspection(List.of(), 0, false));
	}

	@Test
	void with51MessagesOnlyTheFirst50AreShownAndItIsTruncatedWithTheTotal() {
		String queue = queue(null);
		for (int i = 0; i < 51; i++) {
			publisher.publish("", queue, "m" + i);
		}

		QueueInspection inspection = inspector.inspect(queue);

		assertThat(inspection.messages()).hasSize(50);
		assertThat(inspection.messages().get(0).body()).isEqualTo("m0");
		assertThat(inspection.messages().get(49).body()).isEqualTo("m49");
		assertThat(inspection.total()).isEqualTo(51);
		assertThat(inspection.truncated()).isTrue();
		assertThat(counters(queue).ready()).isEqualTo(51);
	}

	@Test
	void exactly50MessagesAreNotTruncated() {
		String queue = queue(null);
		for (int i = 0; i < 50; i++) {
			publisher.publish("", queue, "m" + i);
		}

		QueueInspection inspection = inspector.inspect(queue);

		assertThat(inspection.messages()).hasSize(50);
		assertThat(inspection.total()).isEqualTo(50);
		assertThat(inspection.truncated()).isFalse();
	}

	@Test
	void purgingRemovesOnlyTheReadyMessagesAndKeepsTheQueueAndItsBindings() throws Exception {
		String queue = queue(null);
		String exchange = exchange();
		bindings.create(exchange, queue, "rk");
		for (int i = 0; i < 3; i++) {
			publisher.publish(exchange, "rk", "m" + i);
		}
		try (Channel channel = CONNECTIONS.connection().createChannel()) {
			holdOneUnacknowledged(channel, queue);
			assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 2, 1, 3));

			inspector.purge(queue);

			assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 1, 1));
		}
		assertThat(topology.listBindings(exchange, queue)).hasSize(1);
		assertThat(queues.list().stream().map(Queue::name)).contains(queue);
	}

	@Test
	void theMessagesPendingConfirmationAreNotShownByTheInspection() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "pendiente");
		publisher.publish("", queue, "listo");
		try (Channel channel = CONNECTIONS.connection().createChannel()) {
			holdOneUnacknowledged(channel, queue);

			QueueInspection inspection = inspector.inspect(queue);

			assertThat(inspection.messages()).extracting(QueueMessage::body).containsExactly("listo");
			assertThat(inspection.total()).isEqualTo(1);
		}
	}

	@Test
	void aDeadLetterQueueMessageShowsItsOriginQueueReasonAndOriginalRouting() throws Exception {
		String dead = queue(null);
		String origin = queue(dead);
		String exchange = exchange();
		bindings.create(exchange, origin, "rk");
		publisher.publish(exchange, "rk", "se rechaza");
		RabbitMqContainerSupport.managementPost("/api/queues/%2F/" + encode(origin) + "/get",
				"{\"count\":1,\"ackmode\":\"reject_requeue_false\",\"encoding\":\"auto\"}");

		QueueMessage message = inspector.inspect(dead).messages().get(0);

		assertThat(message.body()).isEqualTo("se rechaza");
		assertThat(message.exchange()).isEqualTo(exchange);
		assertThat(message.routingKey()).isEqualTo("rk");
		assertThat(message.deathInfo()).isEqualTo(new DeathInfo(origin, "rejected"));
		assertThat(inspector.inspect(origin).messages()).isEmpty();
	}

	@Test
	void aBinaryMessageIsFlaggedInsteadOfShownAsGarbage() throws Exception {
		String queue = queue(null);
		RabbitMqContainerSupport.managementPost("/api/exchanges/%2F/amq.default/publish",
				"{\"properties\":{},\"routing_key\":\"" + queue + "\",\"payload\":\"/w==\",\"payload_encoding\":\"base64\"}");

		assertThat(inspector.inspect(queue).messages().get(0).body()).isEqualTo("[contenido binario, en base64] /w==");
	}

	@Test
	void missingQueuesAreNotFound() {
		String missing = "no-existe-" + UUID.randomUUID();

		assertThatThrownBy(() -> inspector.inspect(missing)).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «" + missing + "» no existe.");
		assertThatThrownBy(() -> inspector.purge(missing)).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «" + missing + "» no existe.");
	}

	@Test
	void queueNamesWithSpecialCharactersWork() {
		String queue = "q/%ñ " + UUID.randomUUID();
		queues.create(queue, null);
		createdQueues.add(queue);
		publisher.publish("", queue, "hola");

		assertThat(inspector.inspect(queue).messages()).extracting(QueueMessage::body).containsExactly("hola");
		inspector.purge(queue);
		assertThat(inspector.inspect(queue).total()).isZero();
	}

	/** Consume un mensaje con confirmación manual y no lo confirma: queda pendiente mientras el canal siga abierto. */
	private static void holdOneUnacknowledged(Channel channel, String queue) throws Exception {
		CountDownLatch delivered = new CountDownLatch(1);
		channel.basicQos(1);
		channel.basicConsume(queue, false, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, com.rabbitmq.client.Envelope envelope,
					com.rabbitmq.client.AMQP.BasicProperties properties, byte[] body) {
				delivered.countDown();
			}
		});
		assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
	}

	private Queue counters(String queue) {
		return queues.list().stream().filter(candidate -> candidate.name().equals(queue)).findFirst().orElseThrow();
	}

	private String exchange() {
		String name = "it-insp-ex-" + UUID.randomUUID();
		exchanges.create(name, ExchangeType.DIRECT);
		createdExchanges.add(name);
		return name;
	}

	private String queue(String deadLetterQueue) {
		String name = "it-insp-q-" + UUID.randomUUID();
		queues.create(name, deadLetterQueue);
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
