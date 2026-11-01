package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.support.RabbitMqContainerSupport;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.RecoverableConnection;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AmqpConnectionProviderIT {

	private AmqpConnectionProvider provider;

	@AfterEach
	void closeProvider() {
		if (provider != null) {
			provider.close();
		}
	}

	@Test
	void obtainsAnOpenConnectionAndReusesIt() {
		provider = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());

		Connection first = provider.connection();

		assertThat(first.isOpen()).isTrue();
		assertThat(provider.connection()).isSameAs(first);
	}

	@Test
	void afterTheConnectionIsClosedItObtainsANewOne() throws Exception {
		provider = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());
		Connection first = provider.connection();
		first.close();

		Connection second = provider.connection();

		assertThat(second).isNotSameAs(first);
		assertThat(second.isOpen()).isTrue();
	}

	@Test
	void theConnectionDoesNotRecoverByItself() {
		provider = new AmqpConnectionProvider(RabbitMqContainerSupport.properties());

		assertThat(provider.connection()).isNotInstanceOf(RecoverableConnection.class);
	}

	@Test
	void withRabbitMqDownItFailsAsUnavailableQuickly() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		provider = new AmqpConnectionProvider(new RabbitMqProperties("localhost", closedPort,
				RabbitMqContainerSupport.managementPort(), "guest", "guest", "/"));

		long start = System.nanoTime();
		assertThatThrownBy(provider::connection).isInstanceOf(BrokerUnavailableException.class)
			.hasMessage("RabbitMQ no está disponible.");

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void aServerThatNeverAnswersTheHandshakeDoesNotBlockForMoreThanFiveSeconds() throws Exception {
		try (ServerSocket mute = new ServerSocket(0)) {
			provider = new AmqpConnectionProvider(new RabbitMqProperties("localhost", mute.getLocalPort(),
					RabbitMqContainerSupport.managementPort(), "guest", "guest", "/"));

			long start = System.nanoTime();
			assertThatThrownBy(provider::connection).isInstanceOf(BrokerUnavailableException.class);

			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
		}
	}

	@Test
	void wrongCredentialsAreReportedAsRejectedWithoutRevealingThem() {
		RabbitMqProperties good = RabbitMqContainerSupport.properties();
		provider = new AmqpConnectionProvider(new RabbitMqProperties(good.host(), good.port(),
				good.managementPort(), good.username(), "contraseña-secreta-incorrecta", good.virtualHost()));

		assertThatThrownBy(provider::connection).isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ ha rechazado las credenciales configuradas.")
			.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("secreta"));
	}

}
