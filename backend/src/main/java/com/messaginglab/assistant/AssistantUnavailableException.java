package com.messaginglab.assistant;

import com.messaginglab.exception.LabException;

/** El asistente no puede responder ahora (modelo parado, preparándose o caído). El mensaje va en español. */
public class AssistantUnavailableException extends LabException {

	public AssistantUnavailableException(String message) {
		super(message);
	}

	public AssistantUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
