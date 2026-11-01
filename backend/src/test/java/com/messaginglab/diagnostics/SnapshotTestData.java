package com.messaginglab.diagnostics;

import com.messaginglab.diagnostics.Snapshot.Broker;
import com.messaginglab.diagnostics.Snapshot.Consumer;
import com.messaginglab.diagnostics.Snapshot.Emitter;
import com.messaginglab.diagnostics.Snapshot.Exchange;
import com.messaginglab.diagnostics.Snapshot.Load;
import com.messaginglab.diagnostics.Snapshot.Queue;
import com.messaginglab.diagnostics.Snapshot.Run;
import com.messaginglab.diagnostics.Snapshot.Trace;
import java.time.Instant;
import java.util.List;

/** Fotos de ejemplo para las pruebas del asistente. */
public final class SnapshotTestData {

	private SnapshotTestData() {
	}

	public static final Broker UP = new Broker(true, "4.4.0", null);

	public static final Trace TRACE_ON = new Trace(Trace.State.ACTIVE, null);

	public static final Load QUIET = new Load(0, 100, 0);

	public static Snapshot snapshot(List<Queue> queues, List<Emitter> emitters, List<Consumer> consumers) {
		return new Snapshot(Instant.parse("2026-10-31T10:00:00Z"), UP, TRACE_ON, queues, List.of(), emitters, consumers,
				QUIET);
	}

	public static Snapshot with(Broker broker, Trace trace, Load load) {
		return new Snapshot(Instant.parse("2026-10-31T10:00:00Z"), broker, trace, List.of(), List.of(), List.of(), List.of(),
				load);
	}

	public static Queue queue(String name, long ready, int consumers, Double in, Double out) {
		return new Queue(name, ready, 0, null, consumers, in, out);
	}

	public static Queue queueWithDeadLetter(String name, long ready, String deadLetter) {
		return new Queue(name, ready, 0, deadLetter, 1, null, null);
	}

	public static Emitter emitter(String name, Run status, double rate, long unrouted, String stopMessage) {
		return new Emitter(name, status, "cola «x»", rate, 100, unrouted, stopMessage);
	}

	public static Consumer consumer(String name, String queue, Run status, String stopMessage) {
		return new Consumer(name, queue, status, 0, 10, 0, stopMessage);
	}

	public static Exchange exchange(String name, String type, String... queues) {
		return new Exchange(name, type, List.of(queues));
	}

}
