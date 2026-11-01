package com.messaginglab.generator;

/**
 * Estado de un emisor en un instante.
 * @param unrouted mensajes que el sistema aceptó pero no llegaron a ninguna parte
 * @param ratePerSecond ritmo real de los últimos segundos
 * @param stopMessage por qué se ha parado por sí solo, en español; {@code null} si no ha sido así
 */
public record EmitterSnapshot(String id, EmitterSpec spec, EmitterStatus status, long published, long unrouted,
		double ratePerSecond, String stopMessage) {

}
