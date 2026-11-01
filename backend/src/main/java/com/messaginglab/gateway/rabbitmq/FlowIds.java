package com.messaginglab.gateway.rabbitmq;

/**
 * Ids de los nodos del diagrama de RabbitMQ: el tipo, dos puntos y lo que los identifica. Los de publicadores y
 * consumidores se construyen solo con lo que trae el evento (el nombre de la conexión que da RabbitMQ), sin consultar
 * nada; el nombre legible que puso el cliente se resuelve aparte, al dibujar, porque RabbitMQ tarda unos segundos en
 * mostrar las conexiones nuevas en su API de gestión.
 */
final class FlowIds {

	static final String APP_PREFIX = "app:";

	private FlowIds() {
	}

	static String router(String exchange) {
		return "router:" + exchange;
	}

	static String destination(String queue) {
		return "destination:" + queue;
	}

	static String producer(String connection) {
		return "producer:" + connection;
	}

	/** Publicador que se identifica a sí mismo con la propiedad {@code app_id}, como los emisores del generador. */
	static String producerByApp(String appId) {
		return "producer:" + APP_PREFIX + appId;
	}

	static String consumer(String connection, int channel, String queue) {
		return "consumer:" + connection + "|" + channel + "|" + queue;
	}

	/** Lo que sigue al tipo en el id de un nodo. */
	static String key(String id) {
		return id.substring(id.indexOf(':') + 1);
	}

}
