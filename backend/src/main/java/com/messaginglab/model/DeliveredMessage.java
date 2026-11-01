package com.messaginglab.model;

/**
 * Mensaje entregado a un consumidor.
 * @param deliveryTag identifica el mensaje para confirmarlo o rechazarlo
 */
public record DeliveredMessage(long deliveryTag, QueueMessage message) {

}
