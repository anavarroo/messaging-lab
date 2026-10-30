package com.messaginglab.service;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Binding;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reglas de los bindings del laboratorio: el exchange y la cola deben existir, el exchange por defecto no
 * admite bindings, el fanout ignora la routing key y el topic exige un patrón válido.
 */
@Service
public class BindingService {

	private static final Comparator<Binding> ORDER = Comparator.comparing(Binding::exchange)
		.thenComparing(Binding::queue)
		.thenComparing(Binding::routingKey);

	private final BrokerTopology broker;

	public BindingService(BrokerTopology broker) {
		this.broker = broker;
	}

	/** Lista los bindings; {@code exchange} y {@code queue} filtran si no son {@code null}. */
	public List<Binding> list(String exchange, String queue) {
		return broker.listBindings(exchange, queue).stream().sorted(ORDER).toList();
	}

	/**
	 * Crea el binding.
	 * @param routingKey routing key o patrón; {@code null} equivale a vacía y se ignora en un fanout
	 */
	public Creation<Binding> create(String exchange, String queue, String routingKey) {
		requireNames(exchange, queue);
		rejectDefaultExchange(exchange);
		Exchange target = broker.listExchanges()
			.stream()
			.filter(candidate -> candidate.name().equals(exchange))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("El exchange «" + exchange + "» no existe."));
		if (broker.listQueues().stream().noneMatch(candidate -> candidate.name().equals(queue))) {
			throw new NotFoundException("La cola «" + queue + "» no existe.");
		}
		return broker.createBinding(exchange, queue, normalize(target.type(), routingKey));
	}

	/** Elimina el binding; la routing key {@code null} equivale a la vacía. */
	public void delete(String exchange, String queue, String routingKey) {
		requireNames(exchange, queue);
		rejectDefaultExchange(exchange);
		broker.deleteBinding(exchange, queue, routingKey == null ? "" : routingKey);
	}

	private static String normalize(ExchangeType type, String routingKey) {
		String key = routingKey == null ? "" : routingKey;
		switch (type) {
			case FANOUT -> key = "";
			case TOPIC -> TopicPatternValidator.validate(key);
			case DIRECT -> NameValidator.validateRoutingKey(key, "La routing key");
		}
		return key;
	}

	private static void requireNames(String exchange, String queue) {
		if (exchange == null) {
			throw new ValidationException("Indica el exchange del binding.");
		}
		if (queue == null || queue.isEmpty()) {
			throw new ValidationException("Indica la cola del binding.");
		}
	}

	private static void rejectDefaultExchange(String exchange) {
		if (exchange.isEmpty()) {
			throw new ValidationException(
					"No se pueden crear ni eliminar bindings sobre el exchange por defecto: RabbitMQ enlaza "
							+ "cada cola con él automáticamente, usando su nombre como routing key.");
		}
	}

}
