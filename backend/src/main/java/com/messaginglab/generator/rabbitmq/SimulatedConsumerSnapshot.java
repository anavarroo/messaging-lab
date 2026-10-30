package com.messaginglab.generator.rabbitmq;

/**
 * Estado de un consumidor simulado en un instante.
 * @param processed mensajes confirmados
 * @param failed mensajes que han fallado y se han rechazado
 * @param stopMessage por qué se ha parado por sí solo, en español; {@code null} si no ha sido así
 */
public record SimulatedConsumerSnapshot(String id, SimulatedConsumerSpec spec, Status status, long processed,
		long failed, double ratePerSecond, String stopMessage) {

	public enum Status {

		STOPPED, RUNNING,

		/** Se paró por un error, por ejemplo porque se eliminó la cola. */
		FAILED

	}

}
