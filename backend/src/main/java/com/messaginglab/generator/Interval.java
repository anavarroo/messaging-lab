package com.messaginglab.generator;

import com.messaginglab.exception.ValidationException;
import java.util.random.RandomGenerator;

/**
 * Cada cuánto ocurre algo: un intervalo fijo o uno aleatorio dentro de un rango, en milisegundos.
 */
public record Interval(Mode mode, long fixedMs, long minMs, long maxMs) {

	/** Intervalo mínimo admitido: por debajo de esto el planificador no es fiable. */
	public static final long MIN_MS = 2;

	/** Intervalo máximo admitido: un día. */
	public static final long MAX_MS = 24L * 60 * 60 * 1000;

	public enum Mode {
		FIXED, RANGE
	}

	public static Interval fixed(long ms) {
		return new Interval(Mode.FIXED, ms, ms, ms).validated();
	}

	public static Interval range(long minMs, long maxMs) {
		return new Interval(Mode.RANGE, 0, minMs, maxMs).validated();
	}

	/** Valida un intervalo recibido con valores que pueden faltar. */
	public static Interval of(Mode mode, Long fixedMs, Long minMs, Long maxMs, String subject) {
		if (mode == null) {
			throw new ValidationException("Indica si " + subject + " es fija o está dentro de un rango.");
		}
		if (mode == Mode.FIXED) {
			if (fixedMs == null) {
				throw new ValidationException("Indica el valor fijo de " + subject + ", en milisegundos.");
			}
			return fixed(fixedMs);
		}
		if (minMs == null || maxMs == null) {
			throw new ValidationException("Indica el mínimo y el máximo del rango de " + subject + ", en milisegundos.");
		}
		return range(minMs, maxMs);
	}

	private Interval validated() {
		long low = mode == Mode.FIXED ? fixedMs : minMs;
		long high = mode == Mode.FIXED ? fixedMs : maxMs;
		if (low < MIN_MS || high > MAX_MS) {
			throw new ValidationException("Los intervalos deben estar entre " + MIN_MS + " ms y " + MAX_MS
					+ " ms (un día).");
		}
		if (low > high) {
			throw new ValidationException("El mínimo del rango (" + low + " ms) no puede ser mayor que el máximo ("
					+ high + " ms).");
		}
		return this;
	}

	/** Siguiente espera, en milisegundos. */
	public long next(RandomGenerator random) {
		return mode == Mode.FIXED ? fixedMs : random.nextLong(minMs, maxMs + 1);
	}

	/** Mensajes por segundo en el peor caso: lo que se cuenta contra el límite global. */
	public double maxPerSecond() {
		long shortest = mode == Mode.FIXED ? fixedMs : minMs;
		return 1000.0 / shortest;
	}

}
