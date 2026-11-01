package com.messaginglab.flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Un mensaje publicado o entregado, visto por el visualizador. Es neutral respecto al sistema de mensajería: los
 * campos {@code producer}, {@code router}, {@code destinations} y {@code consumer} son ids de nodos de la topología.
 * En RabbitMQ el enrutador es un exchange y el destino una cola.
 * @param destinations destinos a los que se enrutó la publicación, o el destino del que sale la entrega
 * @param body cuerpo ya truncado, tratado siempre como texto no fiable
 */
public record FlowEvent(long id, Instant time, Type type, String producer, String router, List<String> destinations,
		String consumer, String key, long sizeBytes, boolean redelivered, String body, boolean bodyTruncated,
		Map<String, Object> properties) {

	public enum Type {

		PUBLISH, DELIVER

	}

	/** Una publicación que no llegó a ningún destino. */
	public boolean unrouted() {
		return type == Type.PUBLISH && destinations.isEmpty();
	}

	public FlowEvent withId(long newId) {
		return new FlowEvent(newId, time, type, producer, router, destinations, consumer, key, sizeBytes, redelivered,
				body, bodyTruncated, properties);
	}

}
