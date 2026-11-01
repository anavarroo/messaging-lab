package com.messaginglab.diagnostics;

import static com.messaginglab.diagnostics.SnapshotTestData.QUIET;
import static com.messaginglab.diagnostics.SnapshotTestData.consumer;
import static com.messaginglab.diagnostics.SnapshotTestData.emitter;
import static com.messaginglab.diagnostics.SnapshotTestData.queue;
import static com.messaginglab.diagnostics.SnapshotTestData.snapshot;
import static com.messaginglab.diagnostics.SnapshotTestData.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.diagnostics.Snapshot.Run;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityTest {

	@Test
	void describesWhatIsMovingAndLeavesOutWhatIsStill() {
		Snapshot busy = snapshot(List.of(queue("cocina", 40, 1, 10.0, 5.0), queue("quieta", 0, 1, 0.0, 0.0), queue("sin-datos", 3, 1, null, null)),
				List.of(emitter("Brazo", Run.RUNNING, 10, 0, null), emitter("Parado", Run.STOPPED, 0, 0, null)),
				List.of(consumer("lector", "cocina", Run.RUNNING, null), consumer("dormido", "cocina", Run.STOPPED, null)));

		String text = Activity.describe(busy);

		assertThat(Activity.active(busy)).isTrue();
		assertThat(text).startsWith("Sí, ahora mismo pasan mensajes:")
			.contains("- el emisor «Brazo» publica 10 mensajes por segundo en cola «x».")
			.contains("- el consumidor simulado «lector» lee de la cola «cocina»")
			.contains("- en la cola «cocina» entran 10 mensajes por segundo y salen 5.")
			.doesNotContain("«Parado»").doesNotContain("«dormido»").doesNotContain("«quieta»").doesNotContain("«sin-datos»")
			.endsWith("Puedes verlos viajar en la pestaña «Flujo» del Laboratorio RabbitMQ.");
	}

	@Test
	void aQueueWithOnlyOutgoingMessagesCountsAsMoving() {
		Snapshot draining = snapshot(List.of(queue("cocina", 40, 1, null, 3.0)), List.of(), List.of());

		assertThat(Activity.describe(draining)).contains("en la cola «cocina» entran 0 mensajes por segundo y salen 3.");
	}

	@Test
	void saysNothingIsMovingWhenAllIsStill() {
		Snapshot still = snapshot(List.of(queue("cocina", 0, 1, 0.0, 0.0)), List.of(), List.of());

		assertThat(Activity.active(still)).isFalse();
		assertThat(Activity.describe(still)).startsWith("No, ahora mismo no pasa ningún mensaje");
	}

	@Test
	void withRabbitMqDownSaysSoAndIsNotActive() {
		Snapshot down = with(new Snapshot.Broker(false, null, null), new Snapshot.Trace(Snapshot.Trace.State.UNKNOWN, null), QUIET);

		assertThat(Activity.active(down)).isFalse();
		assertThat(Activity.describe(down)).startsWith("CAUSA: RabbitMQ no está disponible");
	}

}
