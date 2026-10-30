package com.messaginglab.gateway;

import com.messaginglab.model.Binding;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import java.util.List;

/**
 * Gestión de la topología del sistema de mensajería y lectura de su estado.
 * <p>
 * Los fallos se comunican con las excepciones de {@code com.messaginglab.exception}.
 */
public interface BrokerTopology {

	/** Comprueba que se puede comunicar con el sistema de mensajería y devuelve su versión. */
	BrokerStatus status();

	List<Exchange> listExchanges();

	/** Crea el exchange; si ya existe idéntico, lo indica sin duplicarlo; si existe con otro tipo, es un conflicto. */
	Creation<Exchange> createExchange(String name, ExchangeType type);

	void deleteExchange(String name);

	/** Lista las colas con sus contadores actuales. */
	List<Queue> listQueues();

	/**
	 * Crea la cola, durable.
	 * @param deadLetterQueue cola de destino de los mensajes rechazados sin reencolar; {@code null} si no tiene
	 */
	Creation<Queue> createQueue(String name, String deadLetterQueue);

	void deleteQueue(String name);

	/** Elimina los mensajes listos de la cola, manteniendo la cola y sus bindings. */
	void purgeQueue(String name);

	/** Lista los bindings; {@code exchange} y {@code queue} filtran si no son {@code null}. */
	List<Binding> listBindings(String exchange, String queue);

	/** Crea el binding; si ya existe idéntico, lo indica sin duplicarlo. */
	Creation<Binding> createBinding(String exchange, String queue, String routingKey);

	void deleteBinding(String exchange, String queue, String routingKey);

	/** Lee hasta {@code maxMessages} mensajes listos sin retirarlos de la cola. */
	QueueInspection inspectQueue(String queue, int maxMessages);

}
