package com.messaginglab.flow;

/**
 * Estado de la fuente de datos.
 * @param hint qué hacer para activarla, en español; {@code null} si está activa
 */
public record FlowSourceStatus(State state, String hint) {

	public enum State {

		/** El sistema de mensajería no responde. */
		UNAVAILABLE,
		/** Responde, pero el trazado está desactivado. */
		DISABLED,
		ACTIVE

	}

}
