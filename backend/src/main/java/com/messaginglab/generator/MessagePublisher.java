package com.messaginglab.generator;

/**
 * Publicación de los mensajes de los emisores en un sistema de mensajería. Los fallos se comunican con las
 * excepciones de {@code com.messaginglab.exception}.
 */
public interface MessagePublisher {

	/**
	 * Comprueba que el destino existe y es válido.
	 * @throws com.messaginglab.exception.NotFoundException si ya no existe
	 */
	void verify(Destination destination);

	/**
	 * Publica un mensaje.
	 * @return {@code false} si el sistema lo aceptó pero no llegó a ninguna parte
	 * @throws com.messaginglab.exception.NotFoundException si el destino ha dejado de existir
	 */
	boolean publish(Destination destination, String body);

}
