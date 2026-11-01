package com.messaginglab.exception;

/**
 * Error de una operación del laboratorio. El mensaje está en español y va dirigido al usuario,
 * por lo que no debe contener trazas, detalles técnicos internos ni credenciales.
 */
public abstract class LabException extends RuntimeException {

	protected LabException(String message) {
		super(message);
	}

	protected LabException(String message, Throwable cause) {
		super(message, cause);
	}

}
