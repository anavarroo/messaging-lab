package com.messaginglab.generator;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.ValidationException;

/**
 * Límite global de mensajes por segundo, sumando todos los emisores en marcha, para proteger a RabbitMQ de
 * llenarse sin querer. Lo que cuenta de cada emisor es su peor caso: la frecuencia más rápida que puede tener.
 */
public final class GeneratorLimits {

	public static final int DEFAULT_MAX_RATE = 100;

	public static final int ABSOLUTE_MAX_RATE = 500;

	private volatile int maxRate = DEFAULT_MAX_RATE;

	public int maxRate() {
		return maxRate;
	}

	/**
	 * Cambia el límite.
	 * @param inUse mensajes por segundo que ya están en marcha
	 */
	public synchronized void changeMaxRate(int newLimit, double inUse) {
		if (newLimit < 1 || newLimit > ABSOLUTE_MAX_RATE) {
			throw new ValidationException(
					"El límite debe estar entre 1 y " + ABSOLUTE_MAX_RATE + " mensajes por segundo.");
		}
		if (newLimit < inUse - EPSILON) {
			throw new ConflictException("No se puede bajar el límite a " + newLimit + " mensajes por segundo: ya hay "
					+ format(inUse) + " en marcha. Pausa o para algún emisor antes.");
		}
		this.maxRate = newLimit;
	}

	/** Comprueba que añadir {@code extra} mensajes por segundo a los que ya hay no supera el límite. */
	public void checkRoom(String emitter, double extra, double inUse) {
		if (inUse + extra > maxRate + EPSILON) {
			throw new ConflictException("No se puede poner en marcha «" + emitter + "»: añadiría " + format(extra)
					+ " mensajes por segundo a los " + format(inUse) + " que ya están en marcha, y el límite es "
					+ maxRate + ". Para algún emisor o sube el límite (máximo " + ABSOLUTE_MAX_RATE + ").");
		}
	}

	private static final double EPSILON = 1e-9;

	private static String format(double rate) {
		return rate == Math.rint(rate) ? Long.toString((long) rate) : String.format(java.util.Locale.ROOT, "%.1f", rate);
	}

}
