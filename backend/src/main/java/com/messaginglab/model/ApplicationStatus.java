package com.messaginglab.model;

/**
 * Estado del backend, independiente del contrato de la API.
 */
public record ApplicationStatus(State status, String name, String version) {

	public enum State {
		AVAILABLE
	}

}
