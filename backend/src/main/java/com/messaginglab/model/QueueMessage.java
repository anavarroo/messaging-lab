package com.messaginglab.model;

/**
 * Mensaje de una cola.
 * @param exchange exchange por el que llegó; en una dead-letter queue, el original
 * @param routingKey routing key con la que llegó; en una dead-letter queue, la original
 * @param deathInfo solo en mensajes de una dead-letter queue; {@code null} en el resto
 */
public record QueueMessage(String body, String exchange, String routingKey, boolean redelivered,
		DeathInfo deathInfo) {

}
