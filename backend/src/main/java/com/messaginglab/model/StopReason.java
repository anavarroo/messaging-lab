package com.messaginglab.model;

/** Por qué ha terminado un consumidor. */
public enum StopReason {

	/** Lo detuvo el usuario. */
	USER,

	/** Se eliminó la cola que consumía. */
	QUEUE_DELETED,

	/** Se perdió la comunicación con RabbitMQ. */
	CONNECTION_LOST,

	/** Se cerró la sesión (la pestaña) que lo había iniciado. */
	SESSION_CLOSED

}
