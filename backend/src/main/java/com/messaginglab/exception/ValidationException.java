package com.messaginglab.exception;

/** Los datos enviados incumplen una regla del laboratorio. */
public class ValidationException extends LabException {

	public ValidationException(String message) {
		super(message);
	}

}
