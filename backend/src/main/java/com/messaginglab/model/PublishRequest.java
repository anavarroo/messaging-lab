package com.messaginglab.model;

/**
 * Mensaje que se quiere publicar.
 * @param exchange nombre del exchange; vacío para el exchange por defecto, que entrega a la cola cuyo nombre es la routing key
 * @param source quién publica (por ejemplo, el nombre de un emisor del generador), que viaja en la propiedad
 * {@code app_id} del mensaje para que el visualizador de flujo lo reconozca; {@code null} si no se indica
 */
public record PublishRequest(String exchange, String routingKey, String body, String source) {

	public PublishRequest(String exchange, String routingKey, String body) {
		this(exchange, routingKey, body, null);
	}

}
