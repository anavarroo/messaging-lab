package com.messaginglab.generator;

/**
 * Plantilla lista para usar.
 * @param suggestedRoutingKey routing key de ejemplo, con la forma de un topic de MQTT (con puntos en vez de barras)
 * @param onlyWhenChanged si el emisor debe omitir los mensajes iguales al anterior
 */
public record TemplatePreset(String id, String name, String description, String template, Interval suggestedInterval,
		String suggestedRoutingKey, boolean onlyWhenChanged) {

}
