package com.messaginglab.flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Eventos de ejemplo para las pruebas del núcleo del visualizador. */
final class FlowTestData {

	private FlowTestData() {
	}

	static FlowEvent publish(String router, String key, String... destinations) {
		return new FlowEvent(0, Instant.EPOCH, FlowEvent.Type.PUBLISH, "producer:app", router, List.of(destinations),
				null, key, 10, false, "{}", false, Map.of());
	}

	static FlowEvent deliver(String destination, String consumer) {
		return new FlowEvent(0, Instant.EPOCH, FlowEvent.Type.DELIVER, null, null, List.of(destination), consumer, "k",
				10, false, "{}", false, Map.of());
	}

}
