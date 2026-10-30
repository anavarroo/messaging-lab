package com.messaginglab.service;

import com.messaginglab.exception.ValidationException;

/**
 * Patrón de un binding en un exchange de tipo topic: palabras separadas por puntos, donde {@code *}
 * sustituye exactamente una palabra y {@code #} sustituye cero o más palabras.
 * <p>
 * Es más estricto que RabbitMQ para ahorrar errores a quien aprende: no admite palabras vacías ni
 * comodines mezclados con otros caracteres, que RabbitMQ trataría como palabras literales.
 */
public final class TopicPatternValidator {

	private static final String LABEL = "El patrón";

	private TopicPatternValidator() {
	}

	/** Valida un patrón. Vacío es válido. */
	public static void validate(String pattern) {
		NameValidator.validateRoutingKey(pattern, LABEL);
		if (pattern == null || pattern.isEmpty()) {
			return;
		}
		for (String word : pattern.split("\\.", -1)) {
			if (word.isEmpty()) {
				throw new ValidationException(
						"El patrón no puede tener palabras vacías: revisa los puntos al principio, al final o repetidos.");
			}
			boolean mixesWildcard = (word.contains("*") || word.contains("#")) && !word.equals("*") && !word.equals("#");
			if (mixesWildcard) {
				throw new ValidationException("En el patrón, «*» y «#» deben ser una palabra completa, por ejemplo "
						+ "«a.*.b» o «a.#», y no formar parte de otra palabra como «" + word + "».");
			}
		}
	}

}
