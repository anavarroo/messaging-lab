package com.messaginglab.service;

import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reglas de los exchanges del laboratorio. Los predefinidos de RabbitMQ (el exchange por defecto y los
 * {@code amq.*}) solo se pueden consultar.
 */
@Service
public class ExchangeService {

	private static final String OWNER = "del exchange";

	private final BrokerTopology broker;

	public ExchangeService(BrokerTopology broker) {
		this.broker = broker;
	}

	/** Primero los creados por el usuario y después los predefinidos, cada grupo por nombre. */
	public List<Exchange> list() {
		return broker.listExchanges()
			.stream()
			.sorted(Comparator.comparing(Exchange::predefined).thenComparing(Exchange::name))
			.toList();
	}

	public Creation<Exchange> create(String name, ExchangeType type) {
		NameValidator.validateName(name, OWNER);
		if (type == null) {
			throw new ValidationException("Indica el tipo del exchange: direct, fanout o topic.");
		}
		return broker.createExchange(name, type);
	}

	public void delete(String name) {
		if (name == null) {
			throw new ValidationException("Indica el nombre del exchange.");
		}
		if (Exchange.isPredefinedName(name)) {
			throw new ValidationException("Los exchanges predefinidos de RabbitMQ no se pueden eliminar.");
		}
		broker.deleteExchange(name);
	}

}
