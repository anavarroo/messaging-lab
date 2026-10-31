package com.messaginglab.flow;

import java.time.Instant;
import java.util.Map;

/**
 * Resumen de un segundo, indexado por id de nodo.
 * @param published mensajes por segundo publicados
 * @param delivered mensajes por segundo entregados
 * @param ready mensajes listos de cada destino
 * @param omitted eventos que no se enviaron a las pantallas por superar el máximo por segundo
 */
public record FlowTick(Instant time, Map<String, Double> published, Map<String, Double> delivered,
		Map<String, Long> ready, long omitted) {

}
