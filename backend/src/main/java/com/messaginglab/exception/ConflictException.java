package com.messaginglab.exception;

/** La operación choca con el estado actual: ya existe con otra configuración o ya se resolvió. */
public class ConflictException extends LabException {

	public ConflictException(String message) {
		super(message);
	}

}
