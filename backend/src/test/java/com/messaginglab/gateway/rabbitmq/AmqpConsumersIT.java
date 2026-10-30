package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.model.StopReason;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.PublishService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.messaginglab.support.RecordingConsumerListener;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Consumidores AMQP contra un RabbitMQ real. */
class AmqpConsumersIT {

	private static final AmqpConnectionProvider CONNECTIONS = new AmqpConnectionProvider(
			RabbitMqContainerSupport.properties());

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService bindings = new BindingService(topology);

	private final PublishService publisher = new PublishService(topology, new AmqpPublisher(CONNECTIONS));

	private final AmqpConsumers consumers = new AmqpConsumers(CONNECTIONS);

	private final List<String> createdExchanges = new ArrayList<>();

	private final List<String> createdQueues = new ArrayList<>();

	private final List<ConsumerHandle> handles = new ArrayList<>();

	@AfterAll
	static void closeConnection() {
		CONNECTIONS.close();
	}

	@AfterEach
	void cleanUp() {
		handles.forEach(ConsumerHandle::stop);
		createdExchanges.forEach(name -> ignoreMissing(() -> exchanges.delete(name)));
		createdQueues.forEach(name -> ignoreMissing(() -> queues.delete(name)));
	}

	// ---- modo automático (T45)

	@Test
	void anAutomaticConsumerReceivesThePublishedMessagesWithTheirData() throws Exception {
		String queue = queue(null);
		String exchange = exchange();
		bindings.create(exchange, queue, "rk");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		start(queue, AckMode.AUTOMATIC, listener);

		publisher.publish(exchange, "rk", "primero <b>ñ</b> 😀");
		publisher.publish(exchange, "rk", "segundo");

		QueueMessage first = listener.next().message();
		QueueMessage second = listener.next().message();
		assertThat(first).isEqualTo(new QueueMessage("primero <b>ñ</b> 😀", exchange, "rk", false, null));
		assertThat(second.body()).isEqualTo("segundo");
	}

	@Test
	void anAutomaticConsumerConfirmsEachMessageSoTheQueueEmpties() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "uno");
		publisher.publish("", queue, "dos");
		RecordingConsumerListener listener = new RecordingConsumerListener();

		start(queue, AckMode.AUTOMATIC, listener);
		listener.next();
		listener.next();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 0, 0)));
	}

	@Test
	void afterStoppingTheConsumerTheQueueHasNoConsumersAndKeepsLaterMessages() throws Exception {
		String queue = queue(null);
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.AUTOMATIC, listener);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(consumerCount(queue)).isEqualTo(1));

		handle.stop();

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(consumerCount(queue)).isZero());
		publisher.publish("", queue, "después");
		assertThat(listener.nextWithin(500)).isNull();
		assertThat(counters(queue).ready()).isEqualTo(1);
		assertThat(listener.stops()).as("detenerlo el usuario no es un fin ajeno").isEmpty();
	}

	@Test
	void consumingAQueueThatDoesNotExistIsNotFound() {
		assertThatThrownBy(() -> consumers.start("no-existe-" + UUID.randomUUID(), AckMode.MANUAL,
				new RecordingConsumerListener())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void twoAutomaticConsumersOnTheSameQueueShareTheMessages() throws Exception {
		String queue = queue(null);
		RecordingConsumerListener first = new RecordingConsumerListener();
		RecordingConsumerListener second = new RecordingConsumerListener();
		start(queue, AckMode.AUTOMATIC, first);
		start(queue, AckMode.AUTOMATIC, second);

		for (int i = 0; i < 10; i++) {
			publisher.publish("", queue, "m" + i);
		}

		int received = 0;
		int fromFirst = 0;
		int fromSecond = 0;
		for (int i = 0; i < 10; i++) {
			if (first.nextWithin(200) != null) {
				fromFirst++;
			}
			if (second.nextWithin(200) != null) {
				fromSecond++;
			}
		}
		received = fromFirst + fromSecond;
		assertThat(received).isEqualTo(10);
		assertThat(fromFirst).isPositive();
		assertThat(fromSecond).isPositive();
	}

	// ---- modo manual (T46)

	@Test
	void aManualConsumerGetsOnlyOneMessageUntilItResolvesIt() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "uno");
		publisher.publish("", queue, "dos");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);

		DeliveredMessage first = listener.next();

		assertThat(first.message().body()).isEqualTo("uno");
		assertThat(listener.nextWithin(700)).as("no se entrega el siguiente sin resolver el actual").isNull();
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 1, 1, 2));

		handle.resolve(first.deliveryTag(), MessageAction.ACK);

		DeliveredMessage second = listener.next();
		assertThat(second.message().body()).isEqualTo("dos");
	}

	@Test
	void confirmingAMessageRemovesItFromTheQueue() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "uno");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);
		DeliveredMessage message = listener.next();

		handle.resolve(message.deliveryTag(), MessageAction.ACK);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 0, 0)));
	}

	@Test
	void requeueingAMessageReturnsItMarkedAsRedelivered() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "uno");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);
		DeliveredMessage first = listener.next();
		assertThat(first.message().redelivered()).isFalse();

		handle.resolve(first.deliveryTag(), MessageAction.REQUEUE);

		DeliveredMessage again = listener.next();
		assertThat(again.message().body()).isEqualTo("uno");
		assertThat(again.message().redelivered()).isTrue();
	}

	@Test
	void rejectingWithoutADeadLetterQueueDiscardsTheMessage() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "uno");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);
		DeliveredMessage message = listener.next();

		handle.resolve(message.deliveryTag(), MessageAction.REJECT);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 0, 0)));
		assertThat(listener.nextWithin(500)).isNull();
	}

	@Test
	void rejectingWithADeadLetterQueueSendsItThereWithItsOriginInTheDeathHeaders() throws Exception {
		String dead = queue(null);
		String queue = queue(dead);
		String exchange = exchange();
		bindings.create(exchange, queue, "rk");
		publisher.publish(exchange, "rk", "se rechaza");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);
		DeliveredMessage message = listener.next();

		handle.resolve(message.deliveryTag(), MessageAction.REJECT);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(dead).ready()).isEqualTo(1));
		RecordingConsumerListener deadListener = new RecordingConsumerListener();
		start(dead, AckMode.AUTOMATIC, deadListener);
		QueueMessage inDead = deadListener.next().message();
		assertThat(inDead.body()).isEqualTo("se rechaza");
		assertThat(inDead.exchange()).as("exchange original, no el de la dead-letter queue").isEqualTo(exchange);
		assertThat(inDead.routingKey()).isEqualTo("rk");
		assertThat(inDead.deathInfo()).isEqualTo(new DeathInfo(queue, "rejected"));
		assertThat(counters(queue).total()).isZero();
	}

	@Test
	void stoppingAManualConsumerReturnsItsPendingMessageToTheQueue() throws Exception {
		String queue = queue(null);
		publisher.publish("", queue, "pendiente");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = start(queue, AckMode.MANUAL, listener);
		listener.next();
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 0, 1, 1));

		handle.stop();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 1, 0, 1)));
		RecordingConsumerListener another = new RecordingConsumerListener();
		start(queue, AckMode.MANUAL, another);
		assertThat(another.next().message().redelivered()).isTrue();
	}

	@Test
	void twoManualConsumersOnTheSameQueueEachHoldAtMostOneMessage() throws Exception {
		String queue = queue(null);
		RecordingConsumerListener first = new RecordingConsumerListener();
		RecordingConsumerListener second = new RecordingConsumerListener();
		start(queue, AckMode.MANUAL, first);
		start(queue, AckMode.MANUAL, second);

		for (int i = 0; i < 4; i++) {
			publisher.publish("", queue, "m" + i);
		}

		DeliveredMessage toFirst = first.next();
		DeliveredMessage toSecond = second.next();
		assertThat(toFirst.message().body()).isNotEqualTo(toSecond.message().body());
		assertThat(first.nextWithin(500)).isNull();
		assertThat(second.nextWithin(500)).isNull();
		assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 2, 2, 4));
	}

	// ---- fin por causa ajena (T47)

	@Test
	void whenTheQueueIsDeletedTheListenerIsToldQueueDeleted() throws Exception {
		String queue = queue(null);
		RecordingConsumerListener listener = new RecordingConsumerListener();
		start(queue, AckMode.MANUAL, listener);
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(consumerCount(queue)).isEqualTo(1));

		queues.delete(queue);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(listener.stops()).containsExactly(StopReason.QUEUE_DELETED));
	}

	@Test
	void whenTheServerClosesTheConnectionTheListenerIsToldConnectionLost() throws Exception {
		AmqpConnectionProvider own = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());
		AmqpConsumers ownConsumers = new AmqpConsumers(own);
		String queue = queue(null);
		publisher.publish("", queue, "pendiente");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = ownConsumers.start(queue, AckMode.MANUAL, listener);
		handles.add(handle);
		listener.next();

		own.connection().abort();
		own.close();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(listener.stops()).hasSizeGreaterThanOrEqualTo(1));
		assertThat(listener.stops()).containsExactly(StopReason.CONNECTION_LOST);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).isEqualTo(new Queue(queue, null, 1, 0, 1)));
	}

	private ConsumerHandle start(String queue, AckMode mode, RecordingConsumerListener listener) {
		ConsumerHandle handle = consumers.start(queue, mode, listener);
		handles.add(handle);
		return handle;
	}

	private Queue counters(String queue) {
		return queues.list().stream().filter(candidate -> candidate.name().equals(queue)).findFirst().orElseThrow();
	}

	/** Número de consumidores según la API de gestión (se refresca cada pocos segundos). */
	private int consumerCount(String queue) throws Exception {
		String body = RabbitMqContainerSupport.managementGet("/api/queues/%2F/" + encode(queue)).body();
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"consumers\":(\\d+)").matcher(body);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
	}

	private String exchange() {
		String name = "it-cons-ex-" + UUID.randomUUID();
		exchanges.create(name, ExchangeType.DIRECT);
		createdExchanges.add(name);
		return name;
	}

	private String queue(String deadLetterQueue) {
		String name = "it-cons-q-" + UUID.randomUUID();
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
