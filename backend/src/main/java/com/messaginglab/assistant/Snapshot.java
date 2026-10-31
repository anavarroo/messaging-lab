package com.messaginglab.assistant;

import java.time.Instant;
import java.util.List;

/**
 * Foto del laboratorio en un instante: lo único que el asistente sabe del estado de la aplicación. No contiene
 * cuerpos de mensajes (son texto no fiable que podría traer instrucciones dirigidas al modelo).
 * <p>
 * Los textos que se generan a partir de ella hablan de colas y exchanges, las palabras del laboratorio.
 */
public record Snapshot(Instant time, Broker broker, Trace trace, List<Queue> queues, List<Exchange> exchanges,
		List<Emitter> emitters, List<Consumer> consumers, Load load) {

	/** Estado de RabbitMQ; {@code reason} explica por qué no está disponible. */
	public record Broker(boolean available, String version, String reason) {

	}

	public record Trace(State state, String hint) {

		public enum State {

			ACTIVE, DISABLED,
			/** No se puede saber (RabbitMQ no responde). */
			UNKNOWN

		}

	}

	/**
	 * @param consumers consumidores leyendo de la cola ahora; {@code -1} si no se sabe
	 * @param inPerSecond mensajes que entran por segundo; {@code null} si no se sabe (por ejemplo, sin trazado)
	 * @param outPerSecond mensajes que salen por segundo; {@code null} si no se sabe
	 */
	public record Queue(String name, long ready, long unacknowledged, String deadLetterQueue, int consumers,
			Double inPerSecond, Double outPerSecond) {

	}

	/** @param boundQueues colas a las que está enlazado */
	public record Exchange(String name, String type, List<String> boundQueues) {

	}

	public enum Run {

		RUNNING, PAUSED, STOPPED, FINISHED, FAILED

	}

	/** Emisor del generador. {@code stopMessage} explica por qué se paró solo, si fue así. */
	public record Emitter(String name, Run status, String destination, double ratePerSecond, long published,
			long unrouted, String stopMessage) {

	}

	/** Consumidor simulado del generador. */
	public record Consumer(String name, String queue, Run status, double ratePerSecond, long processed, long failed,
			String stopMessage) {

	}

	/**
	 * @param ratePerSecondInUse mensajes por segundo que suman los emisores en marcha
	 * @param maxRatePerSecond límite global de mensajes por segundo
	 * @param omittedPerSecond eventos que el flujo no muestra por segundo por ser demasiados
	 */
	public record Load(double ratePerSecondInUse, int maxRatePerSecond, long omittedPerSecond) {

	}

}
