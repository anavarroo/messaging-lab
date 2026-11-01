package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.BrokerStatus;
import org.junit.jupiter.api.Test;

class ConnectionStatusServiceTest {

	private final BrokerTopology broker = mock(BrokerTopology.class);

	private final ConnectionStatusService service = new ConnectionStatusService(broker);

	@Test
	void returnsTheStatusWhenTheBrokerIsAvailable() {
		when(broker.status()).thenReturn(new BrokerStatus("4.4.0"));

		assertThat(service.status()).isEqualTo(new BrokerStatus("4.4.0"));
	}

	@Test
	void propagatesAnUnavailableBroker() {
		when(broker.status()).thenThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		assertThatThrownBy(service::status).isInstanceOf(BrokerUnavailableException.class);
	}

	@Test
	void propagatesATimeout() {
		when(broker.status()).thenThrow(new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", null));

		assertThatThrownBy(service::status).isInstanceOf(BrokerTimeoutException.class);
	}

	@Test
	void propagatesRejectedCredentials() {
		when(broker.status())
			.thenThrow(new BrokerRejectedException("RabbitMQ ha rechazado las credenciales configuradas."));

		assertThatThrownBy(service::status).isInstanceOf(BrokerRejectedException.class);
	}

}
