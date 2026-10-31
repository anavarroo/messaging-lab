package com.messaginglab.flow;

/** Deja pasar como máximo un número de eventos por segundo y cuenta los que omite. */
public final class EventSampler {

	private final int perSecond;

	private long windowStartMs = Long.MIN_VALUE;

	private int admitted;

	private long omitted;

	public EventSampler(int perSecond) {
		this.perSecond = perSecond;
	}

	public synchronized boolean admit(long nowMs) {
		if (windowStartMs == Long.MIN_VALUE || nowMs - windowStartMs >= 1_000) {
			windowStartMs = nowMs;
			admitted = 0;
		}
		if (admitted < perSecond) {
			admitted++;
			return true;
		}
		omitted++;
		return false;
	}

	/** Eventos omitidos desde la última vez que se preguntó. */
	public synchronized long takeOmitted() {
		long result = omitted;
		omitted = 0;
		return result;
	}

}
