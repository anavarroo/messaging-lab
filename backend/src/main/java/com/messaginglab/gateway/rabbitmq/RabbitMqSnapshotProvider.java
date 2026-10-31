package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.assistant.Snapshot;
import com.messaginglab.assistant.SnapshotProvider;
import com.messaginglab.exception.LabException;
import com.messaginglab.flow.FlowService;
import com.messaginglab.flow.FlowSourceStatus;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.model.Binding;
import com.messaginglab.model.Exchange;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/**
 * La foto del laboratorio para el asistente, con lo que ya sabe la aplicación: la topología de RabbitMQ, los
 * consumidores y ritmos de cada cola (de la API de gestión), el generador y el estado del trazado. Si RabbitMQ no
 * responde, la foto lo dice en vez de fallar.
 */
final class RabbitMqSnapshotProvider implements SnapshotProvider {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> OBJECTS = new ParameterizedTypeReference<>() {
	};

	private final BrokerTopology broker;

	private final ManagementApiClient management;

	private final EmitterService emitters;

	private final SimulatedConsumerService consumers;

	private final FlowService flow;

	private final Clock clock;

	RabbitMqSnapshotProvider(BrokerTopology broker, ManagementApiClient management, EmitterService emitters,
			SimulatedConsumerService consumers, FlowService flow, Clock clock) {
		this.broker = broker;
		this.management = management;
		this.emitters = emitters;
		this.consumers = consumers;
		this.flow = flow;
		this.clock = clock;
	}

	@Override
	public Snapshot current() {
		List<Snapshot.Emitter> emitterStates = emitters.list()
			.stream()
			.map(emitter -> new Snapshot.Emitter(emitter.spec().name(), Snapshot.Run.valueOf(emitter.status().name()),
					emitter.spec().destination().describe(), emitter.ratePerSecond(), emitter.published(), emitter.unrouted(),
					emitter.stopMessage()))
			.toList();
		List<Snapshot.Consumer> consumerStates = consumers.list()
			.stream()
			.map(consumer -> new Snapshot.Consumer(consumer.spec().name(), consumer.spec().queue(),
					Snapshot.Run.valueOf(consumer.status().name()), consumer.ratePerSecond(), consumer.processed(),
					consumer.failed(), consumer.stopMessage()))
			.toList();
		Snapshot.Load load = new Snapshot.Load(emitters.ratePerSecondInUse(), emitters.limits().maxRate(),
				flow.omittedLastSecond());

		Snapshot.Broker state;
		try {
			state = new Snapshot.Broker(true, broker.status().version(), null);
		}
		catch (LabException exception) {
			return new Snapshot(clock.instant(), new Snapshot.Broker(false, null, exception.getMessage()),
					new Snapshot.Trace(Snapshot.Trace.State.UNKNOWN, null), List.of(), List.of(), emitterStates, consumerStates, load);
		}
		try {
			return new Snapshot(clock.instant(), state, trace(), queues(), exchanges(), emitterStates, consumerStates, load);
		}
		catch (LabException exception) {
			// RabbitMQ dejó de responder entre una consulta y otra
			return new Snapshot(clock.instant(), new Snapshot.Broker(false, null, exception.getMessage()),
					new Snapshot.Trace(Snapshot.Trace.State.UNKNOWN, null), List.of(), List.of(), emitterStates, consumerStates, load);
		}
	}

	private Snapshot.Trace trace() {
		FlowSourceStatus status = flow.status();
		return switch (status.state()) {
			case ACTIVE -> new Snapshot.Trace(Snapshot.Trace.State.ACTIVE, null);
			case DISABLED -> new Snapshot.Trace(Snapshot.Trace.State.DISABLED, status.hint());
			case UNAVAILABLE -> new Snapshot.Trace(Snapshot.Trace.State.UNKNOWN, null);
		};
	}

	/** Colas con consumidores y ritmos reales; sin la cola propia con la que el visualizador escucha el trazado. */
	private List<Snapshot.Queue> queues() {
		Map<String, Map<String, Object>> stats = queueStats();
		return broker.listQueues()
			.stream()
			.filter(queue -> !queue.name().startsWith(RabbitMqTraceSource.QUEUE_PREFIX))
			.map(queue -> {
				Map<String, Object> detail = stats.get(queue.name());
				return new Snapshot.Queue(queue.name(), queue.ready(), queue.unacknowledged(), queue.deadLetterQueue(),
						detail == null ? -1 : number(detail.get("consumers"), -1).intValue(),
						detail == null ? null : rate(detail, "publish_details"),
						detail == null ? null : rate(detail, "deliver_get_details"));
			})
			.toList();
	}

	/** Las estadísticas de gestión de cada cola; vacías si no se pueden leer (entonces no se afirma nada de ellas). */
	private Map<String, Map<String, Object>> queueStats() {
		Map<String, Map<String, Object>> byName = new HashMap<>();
		try {
			for (Map<String, Object> queue : management.get("/queues/{vhost}", OBJECTS, management.virtualHost())) {
				byName.put(String.valueOf(queue.get("name")), queue);
			}
		}
		catch (LabException exception) {
			// sin estadísticas: las colas salen sin consumidores ni ritmos conocidos
		}
		return byName;
	}

	/** Mensajes por segundo de un tipo (entrada o salida); 0 si la cola todavía no ha tenido movimiento. */
	private static Double rate(Map<String, Object> queue, String kind) {
		if (queue.get("message_stats") instanceof Map<?, ?> stats && stats.get(kind) instanceof Map<?, ?> details) {
			return number(details.get("rate"), 0).doubleValue();
		}
		return 0.0;
	}

	private static Number number(Object value, Number fallback) {
		return value instanceof Number number ? number : fallback;
	}

	private List<Snapshot.Exchange> exchanges() {
		Map<String, java.util.List<String>> bound = new HashMap<>();
		for (Binding binding : broker.listBindings(null, null)) {
			bound.computeIfAbsent(binding.exchange(), name -> new java.util.ArrayList<>()).add(binding.queue());
		}
		return broker.listExchanges()
			.stream()
			.filter(exchange -> !exchange.predefined())
			.map((Exchange exchange) -> new Snapshot.Exchange(exchange.name(), exchange.type().name().toLowerCase(),
					bound.getOrDefault(exchange.name(), List.of()).stream().distinct().toList()))
			.toList();
	}

}
