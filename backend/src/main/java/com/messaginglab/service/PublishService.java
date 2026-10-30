package com.messaginglab.service;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerPublisher;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

/**
 * Reglas de la publicación: cuerpo no vacío y de hasta 64 KB en UTF-8, routing key de hasta 255 caracteres
 * y exchange existente. El exchange por defecto (nombre vacío) entrega a la cola cuyo nombre es la routing key.
 */
@Service
public class PublishService {

	public static final int MAX_BODY_BYTES = 64 * 1024;

	private final BrokerTopology topology;

	private final BrokerPublisher publisher;

	public PublishService(BrokerTopology topology, BrokerPublisher publisher) {
		this.topology = topology;
		this.publisher = publisher;
	}

	/**
	 * Publica el mensaje.
	 * @param routingKey {@code null} equivale a vacía
	 */
	public PublishResult publish(String exchange, String routingKey, String body) {
		if (exchange == null) {
			throw new ValidationException("Indica el exchange en el que publicar.");
		}
		checkBody(body);
		String key = routingKey == null ? "" : routingKey;
		NameValidator.validateRoutingKey(key, "La routing key");
		if (topology.listExchanges().stream().noneMatch(candidate -> candidate.name().equals(exchange))) {
			throw new NotFoundException("El exchange «" + exchange + "» no existe.");
		}
		return publisher.publish(new PublishRequest(exchange, key, body));
	}

	private static void checkBody(String body) {
		if (body == null || body.isEmpty()) {
			throw new ValidationException("El cuerpo del mensaje no puede estar vacío.");
		}
		if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
			throw new ValidationException(
					"El cuerpo del mensaje no puede ocupar más de 64 KB (" + MAX_BODY_BYTES + " bytes en UTF-8).");
		}
	}

}
