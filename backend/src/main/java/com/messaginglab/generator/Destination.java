package com.messaginglab.generator;

/**
 * Dónde publica un emisor. El núcleo no sabe qué es un destino: cada sistema de mensajería define el suyo (un
 * exchange con su routing key, un topic con su clave...) y su adaptador de {@link MessagePublisher} lo interpreta.
 */
public interface Destination {

	/** Texto legible del destino, para los mensajes dirigidos al usuario. */
	String describe();

}
