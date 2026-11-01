package com.messaginglab.exception;

/** RabbitMQ no ha respondido dentro del tiempo máximo de espera. */
public class BrokerTimeoutException extends LabException {

	public BrokerTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}

}
