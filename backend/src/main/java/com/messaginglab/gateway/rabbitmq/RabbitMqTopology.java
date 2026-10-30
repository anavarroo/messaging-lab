package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Binding;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.exception.NotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

/**
 * Adaptador de {@link BrokerTopology} sobre la API HTTP de gestión de RabbitMQ.
 */
@Component
class RabbitMqTopology implements BrokerTopology {

	private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<List<Map<String, Object>>> OBJECTS = new ParameterizedTypeReference<>() {
	};

	/** Listado ligero: sin estadísticas pero con los contadores de mensajes, que se actualizan al instante. */
	private static final String QUEUES_URI = "/queues/{vhost}?disable_stats=true&enable_queue_totals=true";

	private static final String BINDINGS_OF_PAIR_URI = "/bindings/{vhost}/e/{exchange}/q/{queue}";

	private final ManagementApiClient client;

	RabbitMqTopology(ManagementApiClient client) {
		this.client = client;
	}

	@Override
	public BrokerStatus status() {
		Map<String, Object> overview = client.get("/overview", OBJECT);
		Object version = overview == null ? null : overview.get("rabbitmq_version");
		if (!(version instanceof String text) || text.isBlank()) {
			throw new BrokerRejectedException("RabbitMQ ha devuelto una respuesta que no se puede interpretar.");
		}
		return new BrokerStatus(text);
	}

	/** Solo los tipos direct, fanout y topic; el resto (por ejemplo headers) queda fuera del alcance del laboratorio. */
	@Override
	public List<Exchange> listExchanges() {
		List<Map<String, Object>> exchanges = client.get("/exchanges/{vhost}", OBJECTS, client.virtualHost());
		return exchanges.stream().map(RabbitMqTopology::toExchange).flatMap(Optional::stream).toList();
	}

	@Override
	public Creation<Exchange> createExchange(String name, ExchangeType type) {
		Map<String, Object> definition = Map.of("type", type.name().toLowerCase(Locale.ROOT), "durable", true,
				"auto_delete", false, "internal", false);
		HttpStatusCode status = client.put("/exchanges/{vhost}/{name}", definition, client.virtualHost(), name);
		return new Creation<>(new Exchange(name, type, false), status.value() == 201);
	}

	@Override
	public void deleteExchange(String name) {
		try {
			client.delete("/exchanges/{vhost}/{name}", client.virtualHost(), name);
		}
		catch (NotFoundException exception) {
			throw new NotFoundException("El exchange «" + name + "» no existe.");
		}
	}

	@Override
	public List<Queue> listQueues() {
		List<Map<String, Object>> queues = client.get(QUEUES_URI, OBJECTS, client.virtualHost());
		return queues.stream().map(RabbitMqTopology::toQueue).flatMap(Optional::stream).toList();
	}

	@Override
	public Creation<Queue> createQueue(String name, String deadLetterQueue) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("x-queue-type", "classic");
		if (deadLetterQueue != null) {
			// el exchange por defecto entrega en la cola cuyo nombre es la routing key
			arguments.put("x-dead-letter-exchange", "");
			arguments.put("x-dead-letter-routing-key", deadLetterQueue);
		}
		Map<String, Object> definition = Map.of("durable", true, "auto_delete", false, "arguments", arguments);
		HttpStatusCode status = client.put("/queues/{vhost}/{name}", definition, client.virtualHost(), name);
		if (status.value() == 201) {
			return new Creation<>(new Queue(name, deadLetterQueue, 0, 0, 0), true);
		}
		return new Creation<>(readQueue(name), false);
	}

	@Override
	public void deleteQueue(String name) {
		try {
			client.delete("/queues/{vhost}/{name}", client.virtualHost(), name);
		}
		catch (NotFoundException exception) {
			throw queueNotFound(name);
		}
	}

	@Override
	public void purgeQueue(String name) {
		try {
			client.delete("/queues/{vhost}/{name}/contents", client.virtualHost(), name);
		}
		catch (NotFoundException exception) {
			throw queueNotFound(name);
		}
	}

	/** Solo los bindings de exchanges hacia colas; se omiten los implícitos del exchange por defecto. */
	@Override
	public List<Binding> listBindings(String exchange, String queue) {
		List<Map<String, Object>> bindings = client.get("/bindings/{vhost}", OBJECTS, client.virtualHost());
		return bindings.stream()
			.map(RabbitMqTopology::toBinding)
			.flatMap(Optional::stream)
			.filter(binding -> exchange == null || binding.exchange().equals(exchange))
			.filter(binding -> queue == null || binding.queue().equals(queue))
			.toList();
	}

	/** RabbitMQ responde 201 aunque el binding ya exista, así que antes se consultan los del par. */
	@Override
	public Creation<Binding> createBinding(String exchange, String queue, String routingKey) {
		Binding binding = new Binding(exchange, queue, routingKey);
		List<Map<String, Object>> existing = client.get(BINDINGS_OF_PAIR_URI, OBJECTS, client.virtualHost(),
				exchange, queue);
		boolean alreadyExists = existing.stream()
			.map(RabbitMqTopology::toBinding)
			.flatMap(Optional::stream)
			.anyMatch(binding::equals);
		if (alreadyExists) {
			return new Creation<>(binding, false);
		}
		client.post(BINDINGS_OF_PAIR_URI, Map.of("routing_key", routingKey, "arguments", Map.of()),
				client.virtualHost(), exchange, queue);
		return new Creation<>(binding, true);
	}

	@Override
	public void deleteBinding(String exchange, String queue, String routingKey) {
		try {
			client.delete(BINDINGS_OF_PAIR_URI + "/{key}", client.virtualHost(), exchange, queue,
					propertiesKey(routingKey));
		}
		catch (NotFoundException exception) {
			throw new NotFoundException("El binding indicado no existe.");
		}
	}

	/**
	 * Lee con {@code ack_requeue_true}: los mensajes vuelven a la cola, pero RabbitMQ los marca como
	 * reentregados (limitación conocida, R-1). El total sale de los mensajes leídos más los que
	 * {@code message_count} dice que quedan tras el último.
	 */
	@Override
	public QueueInspection inspectQueue(String queue, int maxMessages) {
		Map<String, Object> request = Map.of("count", maxMessages, "ackmode", "ack_requeue_true", "encoding", "auto");
		List<Map<String, Object>> fetched;
		try {
			fetched = client.post("/queues/{vhost}/{name}/get", request, OBJECTS, client.virtualHost(), queue);
		}
		catch (NotFoundException exception) {
			throw queueNotFound(queue);
		}
		List<QueueMessage> messages = fetched.stream().map(RabbitMqTopology::toMessage).toList();
		long remaining = fetched.isEmpty() ? 0 : count(fetched.get(fetched.size() - 1).get("message_count"));
		long total = messages.size() + remaining;
		return new QueueInspection(messages, total, total > messages.size());
	}

	private static QueueMessage toMessage(Map<String, Object> message) {
		String exchange = text(message.get("exchange"));
		String routingKey = text(message.get("routing_key"));
		DeathInfo death = null;
		Map<?, ?> headers = headers(message.get("properties"));
		if (headers.get("x-first-death-queue") instanceof String originQueue
				&& headers.get("x-first-death-reason") instanceof String reason) {
			death = new DeathInfo(originQueue, reason);
			Map<?, ?> original = originalDeath(headers.get("x-death"), originQueue, reason);
			if (original.get("exchange") instanceof String originalExchange) {
				exchange = originalExchange;
			}
			if (original.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()
					&& keys.get(0) instanceof String originalKey) {
				routingKey = originalKey;
			}
		}
		return new QueueMessage(body(message), exchange, routingKey, Boolean.TRUE.equals(message.get("redelivered")),
				death);
	}

	/** Un contenido que no es texto UTF-8 llega en base64: se avisa en lugar de mostrar bytes ilegibles. */
	private static String body(Map<String, Object> message) {
		String payload = text(message.get("payload"));
		return "base64".equals(message.get("payload_encoding")) ? "[contenido binario, en base64] " + payload : payload;
	}

	/** Sin cabeceras, la API de gestión devuelve una lista vacía en lugar de un objeto. */
	private static Map<?, ?> headers(Object properties) {
		if (properties instanceof Map<?, ?> map && map.get("headers") instanceof Map<?, ?> headers) {
			return headers;
		}
		return Map.of();
	}

	/** La entrada de {@code x-death} de la primera muerte del mensaje; si no se identifica, la más antigua. */
	private static Map<?, ?> originalDeath(Object xDeath, String originQueue, String reason) {
		Map<?, ?> oldest = Map.of();
		if (xDeath instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> death) {
					if (originQueue.equals(death.get("queue")) && reason.equals(death.get("reason"))) {
						return death;
					}
					oldest = death;
				}
			}
		}
		return oldest;
	}

	private static String text(Object value) {
		return value instanceof String text ? text : "";
	}

	private Queue readQueue(String name) {
		try {
			Map<String, Object> queue = client.get("/queues/{vhost}/{name}?disable_stats=true&enable_queue_totals=true",
					OBJECT, client.virtualHost(), name);
			return toQueue(queue)
				.orElseThrow(() -> new BrokerRejectedException(
						"RabbitMQ ha devuelto una respuesta que no se puede interpretar."));
		}
		catch (NotFoundException exception) {
			throw queueNotFound(name);
		}
	}

	private static Optional<Queue> toQueue(Map<String, Object> queue) {
		if (!(queue.get("name") instanceof String name)) {
			return Optional.empty();
		}
		long ready = count(queue.get("messages_ready"));
		long unacknowledged = count(queue.get("messages_unacknowledged"));
		return Optional.of(new Queue(name, deadLetterQueue(queue.get("arguments")), ready, unacknowledged,
				ready + unacknowledged));
	}

	/** La dead-letter queue es el destino del exchange por defecto: su routing key. */
	private static String deadLetterQueue(Object arguments) {
		if (arguments instanceof Map<?, ?> map && "".equals(map.get("x-dead-letter-exchange"))
				&& map.get("x-dead-letter-routing-key") instanceof String routingKey) {
			return routingKey;
		}
		return null;
	}

	private static long count(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}

	private static NotFoundException queueNotFound(String name) {
		return new NotFoundException("La cola «" + name + "» no existe.");
	}

	private static Optional<Binding> toBinding(Map<String, Object> binding) {
		if (!(binding.get("source") instanceof String source) || source.isEmpty()
				|| !"queue".equals(binding.get("destination_type"))
				|| !(binding.get("destination") instanceof String destination)
				|| !(binding.get("routing_key") instanceof String routingKey)) {
			return Optional.empty();
		}
		return Optional.of(new Binding(source, destination, routingKey));
	}

	/**
	 * Clave con la que la API de gestión identifica un binding en la ruta: la routing key codificada como
	 * formulario (espacio como «+», «*» como «%2A») y «~» si está vacía. Al pasarla como variable de la URI se
	 * codifica una vez más, y es esa doble codificación la que RabbitMQ espera.
	 */
	static String propertiesKey(String routingKey) {
		if (routingKey.isEmpty()) {
			return "~";
		}
		return URLEncoder.encode(routingKey, StandardCharsets.UTF_8).replace("*", "%2A");
	}

	private static Optional<Exchange> toExchange(Map<String, Object> exchange) {
		if (!(exchange.get("name") instanceof String name) || !(exchange.get("type") instanceof String type)) {
			return Optional.empty();
		}
		return parseType(type).map(parsed -> new Exchange(name, parsed, Exchange.isPredefinedName(name)));
	}

	private static Optional<ExchangeType> parseType(String type) {
		try {
			return Optional.of(ExchangeType.valueOf(type.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

}
