package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.flow.FlowEvent;
import com.rabbitmq.client.LongString;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Traduce los mensajes que RabbitMQ copia al exchange {@code amq.rabbitmq.trace} a eventos de flujo. La routing
 * key es {@code publish.<exchange>} o {@code deliver.<cola>}; el resto va en las cabeceras.
 */
final class TraceEventTranslator {

	static final int MAX_BODY_BYTES = 4_096;

	private static final String PUBLISH = "publish.";

	private static final String DELIVER = "deliver.";

	private final Clock clock;

	private final String ownQueue;

	/** @param ownQueue cola del propio visualizador: sus entregas no se muestran */
	TraceEventTranslator(Clock clock, String ownQueue) {
		this.clock = clock;
		this.ownQueue = ownQueue;
	}

	Optional<FlowEvent> translate(String routingKey, Map<String, Object> headers, byte[] body) {
		if (headers == null) {
			return Optional.empty();
		}
		boolean publish = routingKey.startsWith(PUBLISH);
		boolean deliver = routingKey.startsWith(DELIVER);
		if (!publish && !deliver) {
			return Optional.empty();
		}
		String queue = deliver ? routingKey.substring(DELIVER.length()) : null;
		if (deliver && queue.equals(ownQueue)) {
			return Optional.empty();
		}
		String exchange = text(headers.get("exchange_name"));
		String connection = text(headers.get("connection"));
		List<String> keys = texts(headers.get("routing_keys"));
		boolean truncated = body.length > MAX_BODY_BYTES;
		String text = new String(body, 0, Math.min(body.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
		Map<String, Object> properties = headers.get("properties") instanceof Map<?, ?> map
				? castMap(plain(map)) : new LinkedHashMap<>();
		String router = FlowIds.router(exchange);
		Object appId = properties.get("app_id");
		String producer = appId instanceof String name && !name.isBlank() ? FlowIds.producerByApp(name)
				: FlowIds.producer(connection);
		FlowEvent event = publish
				? new FlowEvent(0, clock.instant(), FlowEvent.Type.PUBLISH, producer, router,
						texts(headers.get("routed_queues")).stream().map(FlowIds::destination).toList(), null,
						keys.isEmpty() ? "" : keys.getFirst(), body.length, false, text, truncated, properties)
				: new FlowEvent(0, clock.instant(), FlowEvent.Type.DELIVER, null, router,
						List.of(FlowIds.destination(queue)), FlowIds.consumer(connection, number(headers.get("channel")), queue),
						keys.isEmpty() ? "" : keys.getFirst(), body.length, number(headers.get("redelivered")) == 1, text,
						truncated, properties);
		return Optional.of(event);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static List<String> texts(Object value) {
		List<String> result = new ArrayList<>();
		if (value instanceof List<?> list) {
			list.forEach(item -> result.add(text(item)));
		}
		return result;
	}

	private static int number(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	/** Convierte los valores del cliente AMQP (cadenas largas, bytes) en tipos que se pueden serializar. */
	private static Object plain(Object value) {
		if (value instanceof LongString string) {
			return string.toString();
		}
		if (value instanceof byte[] bytes) {
			return "[" + bytes.length + " bytes]";
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, item) -> result.put(String.valueOf(key), plain(item)));
			return result;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(TraceEventTranslator::plain).toList();
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

}
