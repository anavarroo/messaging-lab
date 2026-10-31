package com.messaginglab.assistant;

/**
 * Estado del modelo.
 * @param message explicación en español cuando no está listo; {@code null} si lo está
 */
public record LlmStatus(State state, String message) {

	public enum State {

		READY,
		/** Se está descargando o cargando: la primera vez tarda. */
		PREPARING, UNAVAILABLE

	}

	public static LlmStatus ready() {
		return new LlmStatus(State.READY, null);
	}

}
