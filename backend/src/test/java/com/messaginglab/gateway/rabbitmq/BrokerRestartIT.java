package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.Queue;
import com.messaginglab.model.StopReason;
import com.messaginglab.service.PublishService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.messaginglab.support.RecordingConsumerListener;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Detiene y vuelve a levantar un RabbitMQ real con un consumidor activo (CL-3). Usa su propio contenedor, con
 * puertos fijos en el anfitrión para que sigan siendo los mismos tras reiniciarlo, y sin tocar el compartido.
 */
class BrokerRestartIT {

	private static FixedHostPortGenericContainer<?> container;

	private static RabbitMqProperties properties;

	@BeforeAll
	static void startOwnBroker() throws Exception {
		int amqpPort = freePort();
		int managementPort = freePort();
		container = new FixedHostPortGenericContainer<>(RabbitMqContainerSupport.IMAGE)
			.withFixedExposedPort(amqpPort, 5672)
			.withFixedExposedPort(managementPort, 15672)
			.waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
		container.start();
		properties = new RabbitMqProperties("localhost", amqpPort, managementPort, "guest", "guest", "/");
	}

	@AfterAll
	static void stopOwnBroker() {
		if (container != null) {
			container.stop();
		}
	}

	@Test
	void whenRabbitMqStopsTheConsumerEndsAndThePendingMessagesComeBackWhenItRecovers() throws Exception {
		AmqpConnectionProvider connections = new AmqpConnectionProvider(properties);
		RabbitMqTopology topology = new RabbitMqTopology(new ManagementApiClient(properties));
		QueueService queues = new QueueService(topology);
		PublishService publisher = new PublishService(topology, new AmqpPublisher(connections));
		String queue = "it-restart-" + UUID.randomUUID();
		queues.create(queue, null);
		publisher.publish("", queue, "uno");
		publisher.publish("", queue, "dos");
		RecordingConsumerListener listener = new RecordingConsumerListener();
		ConsumerHandle handle = new AmqpConsumers(connections).start(queue, AckMode.MANUAL, listener);
		listener.next();
		assertThat(queues.list()).contains(new Queue(queue, null, 1, 1, 2));

		container.getDockerClient().stopContainerCmd(container.getContainerId()).exec();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(listener.stops()).containsExactly(StopReason.CONNECTION_LOST));
		assertThat(org.assertj.core.api.Assertions.catchThrowable(topology::status))
			.as("con RabbitMQ detenido, el estado falla como no disponible")
			.isInstanceOf(BrokerUnavailableException.class);

		container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
		await().atMost(Duration.ofSeconds(90)).ignoreExceptions().untilAsserted(() -> assertThat(topology.status().version()).isNotBlank());

		await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(
				() -> assertThat(queues.list()).contains(new Queue(queue, null, 2, 0, 2)));
		RecordingConsumerListener afterwards = new RecordingConsumerListener();
		ConsumerHandle again = new AmqpConsumers(connections).start(queue, AckMode.AUTOMATIC, afterwards);
		assertThat(afterwards.next().message().body()).isEqualTo("uno");
		assertThat(afterwards.next().message().body()).isEqualTo("dos");
		again.stop();
		handle.stop();
		connections.close();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
