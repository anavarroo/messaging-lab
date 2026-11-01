package com.messaginglab.service;

import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.QueueInspection;
import org.springframework.stereotype.Service;

/**
 * Inspección y vaciado de los mensajes de una cola. La inspección muestra como máximo los primeros 50
 * mensajes listos e indica si hay más; el vaciado solo elimina los listos.
 */
@Service
public class QueueInspectionService {

	public static final int MAX_MESSAGES = 50;

	private final BrokerTopology broker;

	public QueueInspectionService(BrokerTopology broker) {
		this.broker = broker;
	}

	public QueueInspection inspect(String queue) {
		requireName(queue);
		return broker.inspectQueue(queue, MAX_MESSAGES);
	}

	public void purge(String queue) {
		requireName(queue);
		broker.purgeQueue(queue);
	}

	private static void requireName(String queue) {
		if (queue == null || queue.isEmpty()) {
			throw new ValidationException("Indica el nombre de la cola.");
		}
	}

}
