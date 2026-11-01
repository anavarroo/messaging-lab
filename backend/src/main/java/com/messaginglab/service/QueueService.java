package com.messaginglab.service;

import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Queue;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reglas de las colas del laboratorio: nombres válidos y reglas de la dead-letter queue, que debe ser una
 * cola existente distinta de la propia.
 */
@Service
public class QueueService {

	private static final String OWNER = "de la cola";

	private final BrokerTopology broker;

	public QueueService(BrokerTopology broker) {
		this.broker = broker;
	}

	public List<Queue> list() {
		return broker.listQueues().stream().sorted(Comparator.comparing(Queue::name)).toList();
	}

	/**
	 * Crea la cola, durable.
	 * @param deadLetterQueue cola existente que recibirá los mensajes rechazados sin reencolar; {@code null} si no
	 * debe tener
	 */
	public Creation<Queue> create(String name, String deadLetterQueue) {
		NameValidator.validateName(name, OWNER);
		if (deadLetterQueue != null) {
			checkDeadLetterQueue(name, deadLetterQueue);
		}
		return broker.createQueue(name, deadLetterQueue);
	}

	public void delete(String name) {
		if (name == null) {
			throw new ValidationException("Indica el nombre de la cola.");
		}
		broker.deleteQueue(name);
	}

	private void checkDeadLetterQueue(String name, String deadLetterQueue) {
		if (deadLetterQueue.equals(name)) {
			throw new ValidationException("Una cola no puede ser su propia dead-letter queue.");
		}
		boolean exists = broker.listQueues().stream().anyMatch(queue -> queue.name().equals(deadLetterQueue));
		if (!exists) {
			throw new ValidationException("La dead-letter queue «" + deadLetterQueue + "» no existe.");
		}
	}

}
