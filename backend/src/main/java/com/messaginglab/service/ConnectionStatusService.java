package com.messaginglab.service;

import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.BrokerStatus;
import org.springframework.stereotype.Service;

/**
 * Comprueba si el backend puede comunicarse con RabbitMQ. Si no puede, el fallo llega como una
 * excepción de dominio ({@code BrokerUnavailableException}, {@code BrokerTimeoutException} o
 * {@code BrokerRejectedException}).
 */
@Service
public class ConnectionStatusService {

	private final BrokerTopology broker;

	public ConnectionStatusService(BrokerTopology broker) {
		this.broker = broker;
	}

	public BrokerStatus status() {
		return broker.status();
	}

}
