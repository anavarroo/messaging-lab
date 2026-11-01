package com.messaginglab.diagnostics;

/**
 * Una conclusión ya sacada por las reglas, escrita como frase completa (causa y, si la hay, solución) para que el
 * modelo solo tenga que repetirla con claridad.
 */
public record Finding(Severity severity, Topic topic, String text) {

	public enum Severity {

		/** Impide usar algo: va siempre al contexto. */
		CRITICAL,
		WARNING,
		INFO

	}

}
