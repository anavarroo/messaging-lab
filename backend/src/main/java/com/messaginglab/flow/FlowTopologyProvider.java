package com.messaginglab.flow;

import java.util.Map;

/** Lo que el visualizador necesita saber de la estructura del sistema de mensajería. */
public interface FlowTopologyProvider {

	FlowTopology topology();

	/** Mensajes listos de cada destino, por id de nodo. */
	Map<String, Long> ready();

}
