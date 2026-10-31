package com.messaginglab.flow;

/** Elemento del diagrama. */
public record FlowNode(String id, Kind kind, String label, String detail) {

	public enum Kind {

		PRODUCER, ROUTER, DESTINATION, CONSUMER

	}

}
