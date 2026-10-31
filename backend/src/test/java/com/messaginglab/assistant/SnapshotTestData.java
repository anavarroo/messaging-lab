package com.messaginglab.assistant;

import com.messaginglab.assistant.Snapshot.Broker;
import com.messaginglab.assistant.Snapshot.Consumer;
import com.messaginglab.assistant.Snapshot.Emitter;
import com.messaginglab.assistant.Snapshot.Exchange;
import com.messaginglab.assistant.Snapshot.Load;
import com.messaginglab.assistant.Snapshot.Queue;
import com.messaginglab.assistant.Snapshot.Run;
import com.messaginglab.assistant.Snapshot.Trace;
import java.time.Instant;
import java.util.List;

/** Fotos de ejemplo para las pruebas del asistente. */
final class SnapshotTestData {

	private SnapshotTestData() {
	}

	static final Broker UP = new Broker(true, "4.4.0", null);

	static final Trace TRACE_ON = new Trace(Trace.State.ACTIVE, null);

	static final Load QUIET = new Load(0, 100, 0);

	static Snapshot snapshot(List<Queue> queues, List<Emitter> emitters, List<Consumer> consumers) {
		return new Snapshot(Instant.parse("2026-10-31T10:00:00Z"), UP, TRACE_ON, queues, List.of(), emitters, consumers,
				QUIET);
	}

	static Snapshot with(Broker broker, Trace trace, Load load) {
		return new Snapshot(Instant.parse("2026-10-31T10:00:00Z"), broker, trace, List.of(), List.of(), List.of(), List.of(),
				load);
	}

	static Queue queue(String name, long ready, int consumers, Double in, Double out) {
		return new Queue(name, ready, 0, null, consumers, in, out);
	}

	static Queue queueWithDeadLetter(String name, long ready, String deadLetter) {
		return new Queue(name, ready, 0, deadLetter, 1, null, null);
	}

	static Emitter emitter(String name, Run status, double rate, long unrouted, String stopMessage) {
		return new Emitter(name, status, "cola «x»", rate, 100, unrouted, stopMessage);
	}

	static Consumer consumer(String name, String queue, Run status, String stopMessage) {
		return new Consumer(name, queue, status, 0, 10, 0, stopMessage);
	}

	static Exchange exchange(String name, String type, String... queues) {
		return new Exchange(name, type, List.of(queues));
	}

}
