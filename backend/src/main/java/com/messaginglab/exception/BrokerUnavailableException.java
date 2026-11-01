package com.messaginglab.exception;

/** No se ha podido contactar con RabbitMQ. */
public class BrokerUnavailableException extends LabException {

	public BrokerUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

	public BrokerUnavailableException(String message) {
		super(message);
	}

}
