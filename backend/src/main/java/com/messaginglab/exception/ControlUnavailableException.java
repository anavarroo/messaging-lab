package com.messaginglab.exception;

/** No se pueden parar ni levantar servicios: no hay Docker Compose, o Docker no responde. */
public class ControlUnavailableException extends LabException {

	public ControlUnavailableException(String message) {
		super(message);
	}

	public ControlUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
