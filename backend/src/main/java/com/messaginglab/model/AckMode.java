package com.messaginglab.model;

/** Cómo confirma los mensajes un consumidor. */
public enum AckMode {

	/** Confirma cada mensaje al recibirlo. */
	AUTOMATIC,

	/** El usuario decide qué hacer con cada mensaje. */
	MANUAL

}
