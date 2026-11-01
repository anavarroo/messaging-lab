package com.messaginglab.model;

/**
 * Enlace entre un exchange y una cola.
 * @param routingKey routing key o patrón; vacía si el enlace no la usa o la deja vacía
 */
public record Binding(String exchange, String queue, String routingKey) {

}
