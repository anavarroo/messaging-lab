package com.messaginglab.generator;

/** Estado de un emisor. */
public enum EmitterStatus {

	/** Parado: no publica. */
	STOPPED,

	/** En marcha: publica a su frecuencia. */
	RUNNING,

	/** En pausa: se puede reanudar. */
	PAUSED,

	/** Terminó solo porque alcanzó su límite de mensajes o de duración. */
	FINISHED,

	/** Se paró por un error, por ejemplo porque su destino dejó de existir. */
	FAILED

}
