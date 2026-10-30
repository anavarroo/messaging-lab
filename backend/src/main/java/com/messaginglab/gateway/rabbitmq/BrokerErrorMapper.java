package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.LabException;
import com.messaginglab.exception.NotFoundException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Traduce los fallos de la API de gestión de RabbitMQ a excepciones de dominio con mensajes en español.
 * Los mensajes no incluyen detalles técnicos ni credenciales.
 */
final class BrokerErrorMapper {

	private static final Pattern INEQUIVALENT_ARG = Pattern.compile("inequivalent arg '([^']+)'");

	private static final Map<String, String> ARGUMENT_NAMES = Map.of("type", "el tipo", "durable", "la durabilidad",
			"auto_delete", "el borrado automático", "x-queue-type", "el tipo de cola", "x-dead-letter-exchange",
			"la dead-letter queue", "x-dead-letter-routing-key", "la dead-letter queue");

	private BrokerErrorMapper() {
	}

	static LabException map(RuntimeException exception) {
		if (exception instanceof LabException labException) {
			return labException;
		}
		if (exception instanceof RestClientResponseException response) {
			return fromResponse(response);
		}
		if (exception instanceof ResourceAccessException access) {
			return fromAccessFailure(access);
		}
		return new BrokerRejectedException("RabbitMQ ha devuelto una respuesta que no se puede interpretar.",
				exception);
	}

	/**
	 * Traduce un fallo del cliente AMQP. Un canal cerrado por RabbitMQ con el código 404 indica que el
	 * exchange o la cola ya no existen.
	 */
	static LabException mapAmqp(Exception exception) {
		if (exception instanceof LabException labException) {
			return labException;
		}
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ShutdownSignalException shutdown
					&& shutdown.getReason() instanceof AMQP.Channel.Close close && close.getReplyCode() == 404) {
				return new NotFoundException("El exchange o la cola indicados ya no existen.");
			}
		}
		if (exception instanceof TimeoutException) {
			return new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", exception);
		}
		return new BrokerUnavailableException("RabbitMQ no está disponible.", exception);
	}

	private static LabException fromResponse(RestClientResponseException response) {
		int status = response.getStatusCode().value();
		return switch (status) {
			case 401, 403 -> new BrokerRejectedException("RabbitMQ ha rechazado las credenciales configuradas.",
					response);
			case 404 -> new NotFoundException("El elemento indicado no existe en RabbitMQ.");
			case 400 -> fromBadRequest(response);
			case 502, 503, 504 -> new BrokerUnavailableException("RabbitMQ no está disponible.", response);
			default -> new BrokerRejectedException("RabbitMQ no ha podido completar la operación.", response);
		};
	}

	private static LabException fromBadRequest(RestClientResponseException response) {
		Matcher matcher = INEQUIVALENT_ARG.matcher(response.getResponseBodyAsString());
		if (matcher.find()) {
			String difference = ARGUMENT_NAMES.getOrDefault(matcher.group(1), "su configuración");
			return new ConflictException(
					"Ya existe con ese nombre pero con otra configuración (difiere en " + difference + ").");
		}
		return new BrokerRejectedException("RabbitMQ ha rechazado la operación.", response);
	}

	private static LabException fromAccessFailure(ResourceAccessException access) {
		for (Throwable cause = access; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpConnectTimeoutException) {
				return new BrokerUnavailableException("RabbitMQ no está disponible.", access);
			}
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", access);
			}
		}
		return new BrokerUnavailableException("RabbitMQ no está disponible.", access);
	}

}
