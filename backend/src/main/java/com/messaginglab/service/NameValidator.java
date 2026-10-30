package com.messaginglab.service;

import com.messaginglab.exception.ValidationException;
import java.nio.charset.StandardCharsets;

/**
 * Reglas de nombre del laboratorio: no vacío, hasta 255 caracteres y 255 bytes en UTF-8, sin caracteres
 * de control y sin el prefijo {@code amq.}, que RabbitMQ reserva para sus propios elementos.
 */
public final class NameValidator {

	public static final int MAX_LENGTH = 255;

	private static final String RESERVED_PREFIX = "amq.";

	private NameValidator() {
	}

	/**
	 * Valida el nombre de un exchange o una cola.
	 * @param name nombre que se quiere usar
	 * @param owner a quién pertenece, como aparece en el mensaje: «del exchange» o «de la cola»
	 */
	public static void validateName(String name, String owner) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("El nombre " + owner + " no puede estar vacío.");
		}
		checkSize(name, "El nombre " + owner);
		checkNoControlCharacters(name, "El nombre " + owner);
		if (name.startsWith(RESERVED_PREFIX)) {
			throw new ValidationException("El nombre " + owner + " no puede empezar por «" + RESERVED_PREFIX
					+ "», un prefijo reservado de RabbitMQ.");
		}
	}

	/**
	 * Valida una routing key o un patrón. Vacía es válido.
	 * @param value routing key o patrón
	 * @param label cómo se llama en el mensaje: «La routing key» o «El patrón»
	 */
	public static void validateRoutingKey(String value, String label) {
		if (value == null || value.isEmpty()) {
			return;
		}
		checkSize(value, label);
		checkNoControlCharacters(value, label);
	}

	private static void checkSize(String value, String subject) {
		if (value.length() > MAX_LENGTH) {
			throw new ValidationException(subject + " no puede superar los " + MAX_LENGTH + " caracteres.");
		}
		if (value.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH) {
			throw new ValidationException(subject + " no puede ocupar más de " + MAX_LENGTH + " bytes en UTF-8.");
		}
	}

	private static void checkNoControlCharacters(String value, String subject) {
		if (value.chars().anyMatch(Character::isISOControl)) {
			throw new ValidationException(subject + " no puede contener caracteres de control.");
		}
	}

}
