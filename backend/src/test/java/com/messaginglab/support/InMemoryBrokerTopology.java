package com.messaginglab.support;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Binding;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gateway falso en memoria para probar las reglas del laboratorio sin RabbitMQ.
 * <p>
 * Imita lo que importa de RabbitMQ: crear algo idéntico no lo duplica, crearlo con otra configuración es un
 * conflicto y eliminar o consultar algo inexistente es «no encontrado». Se amplía a medida que se prueban
 * más áreas; lo que aún no se usa en las pruebas lanza {@link UnsupportedOperationException}.
 */
public class InMemoryBrokerTopology implements BrokerTopology {

	private final Map<String, Exchange> exchanges = new LinkedHashMap<>();

	private final Map<String, Queue> queues = new LinkedHashMap<>();

	private final List<Binding> bindings = new ArrayList<>();

	private final Map<String, List<QueueMessage>> readyMessages = new LinkedHashMap<>();

	private final Map<String, Long> unacknowledged = new LinkedHashMap<>();

	public InMemoryBrokerTopology() {
		exchanges.put("", new Exchange("", ExchangeType.DIRECT, true));
		exchanges.put("amq.direct", new Exchange("amq.direct", ExchangeType.DIRECT, true));
		exchanges.put("amq.fanout", new Exchange("amq.fanout", ExchangeType.FANOUT, true));
		exchanges.put("amq.topic", new Exchange("amq.topic", ExchangeType.TOPIC, true));
	}

	@Override
	public BrokerStatus status() {
		return new BrokerStatus("fake");
	}

	@Override
	public List<Exchange> listExchanges() {
		return new ArrayList<>(exchanges.values());
	}

	@Override
	public Creation<Exchange> createExchange(String name, ExchangeType type) {
		Exchange existing = exchanges.get(name);
		if (existing == null) {
			Exchange created = new Exchange(name, type, false);
			exchanges.put(name, created);
			return new Creation<>(created, true);
		}
		if (existing.type() != type) {
			throw new ConflictException("Ya existe con ese nombre pero con otra configuración (difiere en el tipo).");
		}
		return new Creation<>(existing, false);
	}

	@Override
	public void deleteExchange(String name) {
		if (exchanges.remove(name) == null) {
			throw new NotFoundException("El exchange «" + name + "» no existe.");
		}
	}

	@Override
	public List<Queue> listQueues() {
		return queues.keySet().stream().map(this::withCounters).toList();
	}

	@Override
	public Creation<Queue> createQueue(String name, String deadLetterQueue) {
		Queue existing = queues.get(name);
		if (existing == null) {
			queues.put(name, new Queue(name, deadLetterQueue, 0, 0, 0));
			readyMessages.put(name, new ArrayList<>());
			return new Creation<>(withCounters(name), true);
		}
		if (!Objects.equals(existing.deadLetterQueue(), deadLetterQueue)) {
			throw new ConflictException(
					"Ya existe con ese nombre pero con otra configuración (difiere en la dead-letter queue).");
		}
		return new Creation<>(withCounters(name), false);
	}

	@Override
	public void deleteQueue(String name) {
		if (queues.remove(name) == null) {
			throw new NotFoundException("La cola «" + name + "» no existe.");
		}
		readyMessages.remove(name);
		unacknowledged.remove(name);
	}

	@Override
	public void purgeQueue(String name) {
		requireQueue(name);
		readyMessages.get(name).clear();
	}

	/** Prepara la prueba: deja mensajes listos en la cola, en este orden. */
	public void addReadyMessages(String queue, QueueMessage... messages) {
		requireQueue(queue);
		readyMessages.get(queue).addAll(List.of(messages));
	}

	/** Prepara la prueba: simula mensajes pendientes de confirmar por un consumidor. */
	public void setUnacknowledged(String queue, long count) {
		requireQueue(queue);
		unacknowledged.put(queue, count);
	}

	private Queue withCounters(String name) {
		Queue queue = queues.get(name);
		long ready = readyMessages.get(name).size();
		long pending = unacknowledged.getOrDefault(name, 0L);
		return new Queue(name, queue.deadLetterQueue(), ready, pending, ready + pending);
	}

	private void requireQueue(String name) {
		if (!queues.containsKey(name)) {
			throw new NotFoundException("La cola «" + name + "» no existe.");
		}
	}

	@Override
	public List<Binding> listBindings(String exchange, String queue) {
		return bindings.stream()
			.filter(binding -> exchange == null || binding.exchange().equals(exchange))
			.filter(binding -> queue == null || binding.queue().equals(queue))
			.toList();
	}

	@Override
	public Creation<Binding> createBinding(String exchange, String queue, String routingKey) {
		Binding binding = new Binding(exchange, queue, routingKey);
		if (bindings.contains(binding)) {
			return new Creation<>(binding, false);
		}
		bindings.add(binding);
		return new Creation<>(binding, true);
	}

	@Override
	public void deleteBinding(String exchange, String queue, String routingKey) {
		if (!bindings.remove(new Binding(exchange, queue, routingKey))) {
			throw new NotFoundException("El binding indicado no existe.");
		}
	}

	@Override
	public QueueInspection inspectQueue(String queue, int maxMessages) {
		requireQueue(queue);
		List<QueueMessage> ready = readyMessages.get(queue);
		List<QueueMessage> shown = ready.stream().limit(maxMessages).toList();
		return new QueueInspection(shown, ready.size(), ready.size() > shown.size());
	}

}
