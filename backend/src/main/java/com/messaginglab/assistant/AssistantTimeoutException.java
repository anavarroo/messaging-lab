package com.messaginglab.assistant;

import com.messaginglab.exception.LabException;

/** El modelo ha tardado demasiado en responder. */
public class AssistantTimeoutException extends LabException {

	public AssistantTimeoutException() {
		super("El asistente ha tardado demasiado en responder. Inténtalo de nuevo con una pregunta más corta.");
	}

}
