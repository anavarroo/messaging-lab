package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Conexión AMQP compartida con RabbitMQ, creada de forma perezosa y recreada si se ha cerrado.
 * <p>
 * No tiene recuperación automática a propósito: si se corta la conexión, los consumidores deben finalizar
 * con un aviso en lugar de reanudarse en silencio. Que RabbitMQ no esté listo no impide arrancar el backend:
 * la conexión se abre en el primer uso.
 */
@Component
class AmqpConnectionProvider {

	private static final int CONNECTION_TIMEOUT_MILLIS = 5_000;

	private final ConnectionFactory factory;

	private Connection connection;

	AmqpConnectionProvider(RabbitMqProperties properties) {
		this.factory = new ConnectionFactory();
		factory.setHost(properties.host());
		factory.setPort(properties.port());
		factory.setUsername(properties.username());
		factory.setPassword(properties.password());
		factory.setVirtualHost(properties.virtualHost());
		factory.setAutomaticRecoveryEnabled(false);
		factory.setTopologyRecoveryEnabled(false);
		factory.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
		factory.setHandshakeTimeout(CONNECTION_TIMEOUT_MILLIS);
	}

	/** Devuelve la conexión abierta, creando una nueva si no hay o está cerrada. */
	synchronized Connection connection() {
		if (connection == null || !connection.isOpen()) {
			connection = open();
		}
		return connection;
	}

	private Connection open() {
		try {
			return factory.newConnection("messaging-lab");
		}
		catch (PossibleAuthenticationFailureException exception) {
			throw new BrokerRejectedException("RabbitMQ ha rechazado las credenciales configuradas.", exception);
		}
		catch (IOException | TimeoutException exception) {
			throw new BrokerUnavailableException("RabbitMQ no está disponible.", exception);
		}
	}

	@PreDestroy
	synchronized void close() {
		if (connection != null && connection.isOpen()) {
			try {
				connection.close();
			}
			catch (IOException | RuntimeException exception) {
				// se está apagando el backend: no hay nada útil que hacer con el fallo
			}
		}
		connection = null;
	}

}
