package com.messaginglab.model;

/**
 * Cola de RabbitMQ con sus contadores.
 * @param deadLetterQueue cola a la que van los mensajes rechazados sin reencolar; {@code null} si no tiene
 * @param ready mensajes listos para entregar
 * @param unacknowledged mensajes entregados a un consumidor y pendientes de confirmar
 * @param total listos más pendientes
 */
public record Queue(String name, String deadLetterQueue, long ready, long unacknowledged, long total) {

}
