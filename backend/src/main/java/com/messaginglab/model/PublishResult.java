package com.messaginglab.model;

/**
 * Resultado de publicar un mensaje que RabbitMQ ha aceptado.
 * @param routed {@code false} si ninguna cola lo ha recibido y se ha descartado
 */
public record PublishResult(boolean routed) {

}
