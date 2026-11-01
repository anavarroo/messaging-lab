package com.messaginglab.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que se mueve ahora mismo en el laboratorio, en español. Es un recuento exacto que se da tal cual, sin pasar por un
 * modelo de IA.
 */
public final class Activity {

	private Activity() {
	}

	/** ¿Pasan mensajes ahora? */
	public static boolean active(Snapshot snapshot) {
		return snapshot.broker().available() && !lines(snapshot).isEmpty();
	}

	public static String describe(Snapshot snapshot) {
		if (!snapshot.broker().available()) {
			return Diagnostics.analyze(snapshot).getFirst().text();
		}
		List<String> lines = lines(snapshot);
		if (lines.isEmpty()) {
			return "No, ahora mismo no pasa ningún mensaje: no hay emisores ni consumidores simulados en marcha y ninguna cola "
					+ "tiene entradas ni salidas. Si acabas de publicar, RabbitMQ tarda unos segundos en mostrar el movimiento.";
		}
		return "Sí, ahora mismo pasan mensajes:\n" + String.join("\n", lines)
				+ "\nPuedes verlos viajar en la pestaña «Flujo» del Laboratorio RabbitMQ.";
	}

	/** Una línea por cada cosa que se mueve: emisores y consumidores en marcha, y colas con entrada o salida. */
	private static List<String> lines(Snapshot snapshot) {
		List<String> lines = new ArrayList<>();
		snapshot.emitters().stream().filter(emitter -> emitter.status() == Snapshot.Run.RUNNING).forEach(emitter -> lines.add(
				"- el emisor «" + emitter.name() + "» publica " + Diagnostics.number(emitter.ratePerSecond()) + " mensajes por segundo en "
						+ emitter.destination() + "."));
		snapshot.consumers().stream().filter(consumer -> consumer.status() == Snapshot.Run.RUNNING).forEach(consumer -> lines.add(
				"- el consumidor simulado «" + consumer.name() + "» lee de la cola «" + consumer.queue() + "» ("
						+ Diagnostics.number(consumer.ratePerSecond()) + " por segundo)."));
		snapshot.queues()
			.stream()
			.filter(queue -> (queue.inPerSecond() != null && queue.inPerSecond() > 0)
					|| (queue.outPerSecond() != null && queue.outPerSecond() > 0))
			.forEach(queue -> lines.add("- en la cola «" + queue.name() + "» entran " + Diagnostics.number(orZero(queue.inPerSecond()))
					+ " mensajes por segundo y salen " + Diagnostics.number(orZero(queue.outPerSecond())) + "."));
		return lines;
	}

	private static double orZero(Double value) {
		return value == null ? 0 : value;
	}

}
