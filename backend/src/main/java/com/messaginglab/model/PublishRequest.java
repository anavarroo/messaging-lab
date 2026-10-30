package com.messaginglab.model;

/**
 * Mensaje que se quiere publicar.
 * @param exchange nombre del exchange; vacío para el exchange por defecto, que entrega a la cola cuyo nombre es la routing key
 */
public record PublishRequest(String exchange, String routingKey, String body) {

}
