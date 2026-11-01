package com.messaginglab.flow;

import java.util.List;

/** Nodos del diagrama y las conexiones entre ellos. */
public record FlowTopology(List<FlowNode> nodes, List<Edge> edges) {

	public static final FlowTopology EMPTY = new FlowTopology(List.of(), List.of());

	/** Conexión de un nodo a otro; {@code label} es opcional (por ejemplo, el patrón de un binding). */
	public record Edge(String from, String to, String label) {

	}

}
