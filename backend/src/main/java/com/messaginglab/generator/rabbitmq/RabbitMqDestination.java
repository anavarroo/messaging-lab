package com.messaginglab.generator.rabbitmq;

import com.messaginglab.exception.ValidationException;
import com.messaginglab.generator.Destination;
import com.messaginglab.service.NameValidator;

/**
 * Destino de un emisor en RabbitMQ: un exchange con su routing key, o directamente una cola (que se publica por el
 * exchange por defecto, usando el nombre de la cola como routing key).
 */
public record RabbitMqDestination(Type type, String exchange, String routingKey, String queue) implements Destination {

	public enum Type {
		EXCHANGE, QUEUE
	}

	public static RabbitMqDestination toExchange(String exchange, String routingKey) {
		if (exchange == null || exchange.isEmpty()) {
			throw new ValidationException("Indica el exchange de destino.");
		}
		String key = routingKey == null ? "" : routingKey;
		NameValidator.validateRoutingKey(key, "La routing key");
		return new RabbitMqDestination(Type.EXCHANGE, exchange, key, null);
	}

	public static RabbitMqDestination toQueue(String queue) {
		if (queue == null || queue.isEmpty()) {
			throw new ValidationException("Indica la cola de destino.");
		}
		return new RabbitMqDestination(Type.QUEUE, null, null, queue);
	}

	/** Exchange en el que se publica de verdad: el por defecto (vacío) para una cola. */
	String publishExchange() {
		return type == Type.QUEUE ? "" : exchange;
	}

	String publishRoutingKey() {
		return type == Type.QUEUE ? queue : routingKey;
	}

	@Override
	public String describe() {
		return type == Type.QUEUE ? "la cola «" + queue + "»"
				: "el exchange «" + exchange + "»" + (routingKey.isEmpty() ? "" : " con la routing key «" + routingKey + "»");
	}

}
