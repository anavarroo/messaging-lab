package com.messaginglab.flow;

/** Lo que recibe una pantalla suscrita. */
public sealed interface FlowMessage {

	record Event(FlowEvent event) implements FlowMessage {

	}

	record Tick(FlowTick tick) implements FlowMessage {

	}

	record Status(FlowSourceStatus status) implements FlowMessage {

	}

	record Topology(FlowTopology topology) implements FlowMessage {

	}

}
