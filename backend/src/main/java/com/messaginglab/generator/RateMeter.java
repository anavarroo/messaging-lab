package com.messaginglab.generator;

import java.util.ArrayDeque;
import java.util.Deque;

/** Mide el ritmo real, en eventos por segundo, sobre los últimos segundos. */
public final class RateMeter {

	private static final long WINDOW_MS = 5_000;

	private final Deque<Long> events = new ArrayDeque<>();

	private long since = -1;

	public void record(long nowMs) {
		if (since < 0) {
			since = nowMs;
		}
		events.addLast(nowMs);
		trim(nowMs);
	}

	/** Eventos por segundo: los de la ventana, o los de menos tiempo si acaba de empezar. */
	public double perSecond(long nowMs) {
		trim(nowMs);
		if (events.isEmpty()) {
			return 0;
		}
		long span = Math.min(WINDOW_MS, Math.max(nowMs - since, 1_000));
		return events.size() * 1000.0 / span;
	}

	public void reset() {
		events.clear();
		since = -1;
	}

	private void trim(long nowMs) {
		while (!events.isEmpty() && nowMs - events.peekFirst() > WINDOW_MS) {
			events.removeFirst();
		}
	}

}
