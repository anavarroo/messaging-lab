package com.messaginglab.model;

/**
 * Exchange de RabbitMQ.
 * @param predefined es uno de los que trae RabbitMQ (el exchange por defecto y los {@code amq.*}); no se modifica
 */
public record Exchange(String name, ExchangeType type, boolean predefined) {

	/** El exchange por defecto (nombre vacío) y los que empiezan por {@code amq.} son de RabbitMQ. */
	public static boolean isPredefinedName(String name) {
		return name.isEmpty() || name.startsWith("amq.");
	}

}
