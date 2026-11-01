package com.messaginglab.gateway.rabbitmq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos de conexión con RabbitMQ. Los valores salen de las variables RABBITMQ_* (ver application.properties).
 */
@ConfigurationProperties("messaginglab.rabbitmq")
public record RabbitMqProperties(String host, int port, int managementPort, String username, String password,
		String virtualHost) {

	/** La contraseña nunca debe aparecer en logs ni en mensajes de error. */
	@Override
	public String toString() {
		return "RabbitMqProperties[host=" + host + ", port=" + port + ", managementPort=" + managementPort
				+ ", username=" + username + ", password=***, virtualHost=" + virtualHost + "]";
	}

}
