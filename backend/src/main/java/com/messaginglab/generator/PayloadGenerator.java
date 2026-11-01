package com.messaginglab.generator;

import com.messaginglab.exception.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Genera los cuerpos de un emisor a partir de su plantilla. Guarda el estado de los valores que lo necesitan
 * (contadores, derivas, ejes), por lo que cada emisor tiene el suyo. No es seguro para hilos: lo usa un único hilo.
 */
public final class PayloadGenerator {

	private final List<Part> parts;

	private final RandomGenerator random;

	private final Clock clock;

	private final Instant start;

	PayloadGenerator(List<Part> parts, RandomGenerator random, Clock clock) {
		this.parts = parts;
		this.random = random;
		this.clock = clock;
		this.start = clock.instant();
	}

	/** El siguiente cuerpo. */
	public String next() {
		Instant now = clock.instant();
		PayloadTemplate.Context context = new PayloadTemplate.Context(random, now,
				Duration.between(start, now).toNanos() / 1_000_000_000.0);
		StringBuilder body = new StringBuilder();
		for (Part part : parts) {
			body.append(part.next(context));
		}
		String result = body.toString();
		int bytes = PayloadTemplate.bytesOf(result);
		if (bytes > PayloadTemplate.MAX_BYTES) {
			throw new ValidationException("El cuerpo generado ocupa " + bytes + " bytes y el máximo es "
					+ PayloadTemplate.MAX_BYTES + " (64 KB). Acorta la plantilla.");
		}
		return result;
	}

	/** Un trozo de la plantilla: un texto fijo o un valor que cambia. */
	@FunctionalInterface
	interface Part {

		String next(PayloadTemplate.Context context);

	}

}
