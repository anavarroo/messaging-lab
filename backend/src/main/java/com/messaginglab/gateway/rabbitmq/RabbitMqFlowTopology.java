package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.LabException;
import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowNode;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.flow.FlowTopologyProvider;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.Queue;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Estructura que dibuja el visualizador: exchanges, colas y bindings de RabbitMQ, los consumidores conectados y los
 * publicadores y consumidores observados en el trazado. Los nombres legibles de publicadores y consumidores salen de
 * las conexiones y consumidores de la API de gestión, cruzados con el nombre de conexión que trae cada evento.
 * <p>
 * Los datos de gestión se guardan unos segundos para no consultarla en cada evento.
 */
final class RabbitMqFlowTopology implements FlowTopologyProvider {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> OBJECTS = new ParameterizedTypeReference<>() {
	};

	private static final long QUEUES_MAX_AGE_MS = 900;

	private static final long DETAILS_MAX_AGE_MS = 5_000;

	/** Tiempo sin actividad tras el cual un publicador o consumidor observado sale del diagrama. */
	private static final long SEEN_TTL_MS = 5 * 60_000;

	private final BrokerTopology broker;

	private final ManagementApiClient management;

	private final Clock clock;

	private List<Queue> queues = List.of();

	private List<Exchange> exchanges = List.of();

	private List<com.messaginglab.model.Binding> bindings = List.of();

	/** Nombre de la conexión según RabbitMQ (dirección origen y destino) al nombre que puso el cliente. */
	private Map<String, String> connections = Map.of();

	private List<Map<String, Object>> consumers = List.of();

	/** Marca de «nunca consultado»: lo bastante antigua para que se consulte, sin desbordar al restar. */
	private static final long NEVER = Long.MIN_VALUE / 2;

	private long queuesAt = NEVER;

	private long detailsAt = NEVER;

	/** Nodos observados en eventos (id a tipo y última vez) y sus conexiones. */
	private final Map<String, Seen> seenNodes = new LinkedHashMap<>();

	private final Map<String, Seen> seenEdges = new LinkedHashMap<>();

	RabbitMqFlowTopology(BrokerTopology broker, ManagementApiClient management, Clock clock) {
		this.broker = broker;
		this.management = management;
		this.clock = clock;
	}

	/** Recuerda a los publicadores y consumidores de un evento para dibujarlos aunque no sigan conectados. */
	synchronized void observe(FlowEvent event) {
		long now = clock.millis();
		if (event.type() == FlowEvent.Type.PUBLISH) {
			see(event.producer(), FlowNode.Kind.PRODUCER, now);
			edge(event.producer(), event.router(), now);
		}
		else {
			see(event.consumer(), FlowNode.Kind.CONSUMER, now);
			event.destinations().forEach(destination -> edge(destination, event.consumer(), now));
		}
	}

	@Override
	public synchronized FlowTopology topology() {
		refresh();
		long now = clock.millis();
		seenNodes.values().removeIf(seen -> now - seen.at > SEEN_TTL_MS);
		seenEdges.values().removeIf(seen -> now - seen.at > SEEN_TTL_MS);
		Map<String, FlowNode> nodes = new LinkedHashMap<>();
		Set<FlowTopology.Edge> edges = new LinkedHashSet<>();
		nodes.put(FlowIds.router(""), new FlowNode(FlowIds.router(""), FlowNode.Kind.ROUTER, "(exchange por defecto)", "direct"));
		for (Exchange exchange : exchanges) {
			String id = FlowIds.router(exchange.name());
			nodes.put(id, new FlowNode(id, FlowNode.Kind.ROUTER, exchange.name().isEmpty() ? "(exchange por defecto)" : exchange.name(),
					exchange.type().name().toLowerCase()));
		}
		for (Queue queue : visibleQueues()) {
			String id = FlowIds.destination(queue.name());
			nodes.put(id, new FlowNode(id, FlowNode.Kind.DESTINATION, queue.name(),
					queue.deadLetterQueue() == null ? null : "dead-letter: " + queue.deadLetterQueue()));
		}
		for (var binding : bindings) {
			edges.add(new FlowTopology.Edge(FlowIds.router(binding.exchange()), FlowIds.destination(binding.queue()),
					binding.routingKey()));
		}
		for (Map<String, Object> consumer : consumers) {
			String id = consumerId(consumer);
			nodes.put(id, new FlowNode(id, FlowNode.Kind.CONSUMER, consumerLabel(consumer), null));
			edges.add(new FlowTopology.Edge(FlowIds.destination(queueOf(consumer)), id, null));
		}
		seenNodes.forEach((id, seen) -> nodes.putIfAbsent(id, new FlowNode(id, seen.kind, labelOf(id, seen.kind), null)));
		seenEdges.values().forEach(seen -> edges.add(new FlowTopology.Edge(seen.from, seen.to, null)));
		edges.removeIf(edge -> !nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()));
		return new FlowTopology(List.copyOf(nodes.values()), List.copyOf(edges));
	}

	@Override
	public synchronized Map<String, Long> ready() {
		refreshQueues();
		Map<String, Long> ready = new HashMap<>();
		visibleQueues().forEach(queue -> ready.put(FlowIds.destination(queue.name()), queue.ready()));
		return ready;
	}

	/** Las colas del laboratorio, sin la cola propia con la que el visualizador escucha el trazado. */
	private List<Queue> visibleQueues() {
		return queues.stream().filter(queue -> !queue.name().startsWith(RabbitMqTraceSource.QUEUE_PREFIX)).toList();
	}

	private void refresh() {
		refreshQueues();
		refreshDetails();
	}

	private void refreshQueues() {
		if (clock.millis() - queuesAt < QUEUES_MAX_AGE_MS) {
			return;
		}
		try {
			queues = broker.listQueues();
			queuesAt = clock.millis();
		}
		catch (LabException exception) {
			// RabbitMQ no responde: se sigue mostrando lo último que se supo
		}
	}

	private void refreshDetails() {
		if (clock.millis() - detailsAt < DETAILS_MAX_AGE_MS) {
			return;
		}
		try {
			exchanges = broker.listExchanges();
			bindings = broker.listBindings(null, null);
			Map<String, String> names = new HashMap<>();
			for (Map<String, Object> connection : management.get("/connections", OBJECTS)) {
				names.put(String.valueOf(connection.get("name")), clientName(connection));
			}
			connections = names;
			consumers = management.get("/consumers/{vhost}", OBJECTS, management.virtualHost());
			detailsAt = clock.millis();
		}
		catch (LabException exception) {
			// RabbitMQ no responde: se sigue mostrando lo último que se supo
		}
	}

	private String connectionLabel(String connection) {
		return connections.getOrDefault(connection, connection);
	}

	/** Nombre legible de un publicador o un consumidor observado; el de la conexión mientras RabbitMQ no la muestre. */
	private String labelOf(String id, FlowNode.Kind kind) {
		String key = FlowIds.key(id);
		if (kind == FlowNode.Kind.PRODUCER) {
			return key.startsWith(FlowIds.APP_PREFIX) ? key.substring(FlowIds.APP_PREFIX.length()) : connectionLabel(key);
		}
		String[] parts = key.split("\\|", 3);
		for (Map<String, Object> consumer : consumers) {
			if (id.equals(consumerId(consumer))) {
				return consumerLabel(consumer);
			}
		}
		return connectionLabel(parts[0]);
	}

	private static String consumerId(Map<String, Object> consumer) {
		Map<?, ?> details = consumer.get("channel_details") instanceof Map<?, ?> map ? map : Map.of();
		int channel = details.get("number") instanceof Number number ? number.intValue() : 0;
		return FlowIds.consumer(String.valueOf(details.get("connection_name")), channel, queueOf(consumer));
	}

	/** El nombre que puso el cliente al conectarse, o el de RabbitMQ (dirección origen y destino) si no puso ninguno. */
	private static String clientName(Map<String, Object> connection) {
		if (connection.get("client_properties") instanceof Map<?, ?> properties
				&& properties.get("connection_name") instanceof String name && !name.isBlank()) {
			return name;
		}
		return String.valueOf(connection.get("name"));
	}

	private String consumerLabel(Map<String, Object> consumer) {
		String connection = consumer.get("channel_details") instanceof Map<?, ?> details
				? String.valueOf(details.get("connection_name")) : "";
		String tag = String.valueOf(consumer.get("consumer_tag"));
		String owner = connectionLabel(connection);
		if (tag.startsWith("amq.ctag-")) {
			return owner;
		}
		// los consumidores del propio laboratorio se reconocen por su nombre; los ajenos, por su conexión y su etiqueta
		return owner.equals(AmqpConnectionProvider.CONNECTION_NAME) ? tag : owner + " · " + tag;
	}

	private static String queueOf(Map<String, Object> consumer) {
		return consumer.get("queue") instanceof Map<?, ?> queue ? String.valueOf(queue.get("name")) : "";
	}

	private void see(String id, FlowNode.Kind kind, long now) {
		if (id != null) {
			seenNodes.put(id, new Seen(kind, null, null, now));
		}
	}

	private void edge(String from, String to, long now) {
		if (from != null && to != null) {
			seenEdges.put(from + ">" + to, new Seen(null, from, to, now));
		}
	}

	private record Seen(FlowNode.Kind kind, String from, String to, long at) {

	}

}
