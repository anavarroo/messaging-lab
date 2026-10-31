package com.messaginglab.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Conserva en memoria los últimos eventos; al llenarse descarta los más antiguos. */
public final class FlowEventBuffer {

	private final int capacity;

	private final Deque<FlowEvent> events = new ArrayDeque<>();

	public FlowEventBuffer(int capacity) {
		this.capacity = capacity;
	}

	public synchronized void add(FlowEvent event) {
		if (events.size() == capacity) {
			events.removeFirst();
		}
		events.addLast(event);
	}

	/** Hasta {@code limit} eventos, el más reciente primero. */
	public synchronized List<FlowEvent> recent(int limit) {
		List<FlowEvent> result = new ArrayList<>(Math.min(limit, events.size()));
		var iterator = events.descendingIterator();
		while (iterator.hasNext() && result.size() < limit) {
			result.add(iterator.next());
		}
		return result;
	}

}
