package com.messaginglab.exception;

/** RabbitMQ ha respondido, pero rechazando la operación (por ejemplo, por credenciales no válidas). */
public class BrokerRejectedException extends LabException {

	public BrokerRejectedException(String message) {
		super(message);
	}

	public BrokerRejectedException(String message, Throwable cause) {
		super(message, cause);
	}

}
