package com.messaginglab.model;

/** Qué hace el usuario con un mensaje pendiente de confirmar. */
public enum MessageAction {

	/** Confirmarlo: sale de la cola. */
	ACK,

	/** Rechazarlo y devolverlo a la cola. */
	REQUEUE,

	/** Rechazarlo sin reencolar: va a la dead-letter queue si la cola tiene una, y si no se descarta. */
	REJECT

}
