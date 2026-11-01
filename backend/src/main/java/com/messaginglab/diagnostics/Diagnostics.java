package com.messaginglab.diagnostics;

import com.messaginglab.diagnostics.Finding.Severity;
import com.messaginglab.diagnostics.Snapshot.Consumer;
import com.messaginglab.diagnostics.Snapshot.Emitter;
import com.messaginglab.diagnostics.Snapshot.Queue;
import com.messaginglab.diagnostics.Snapshot.Run;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reglas deterministas que convierten una foto del laboratorio en conclusiones. Aquí está el razonamiento: el
 * modelo no tiene que deducir nada. Cada conclusión es una frase completa de causa y, cuando la hay, de solución,
 * y no deja negaciones ni cifras sueltas que un modelo pequeño pueda malinterpretar.
 */
public final class Diagnostics {

	/** Una cola «crece» si entran al menos tantos mensajes más por segundo que los que salen. */
	static final double MIN_GROWTH_PER_SECOND = 1.0;

	/** Una cola «se vacía» si salen al menos tantos mensajes más por segundo que los que entran, y tiene algo. */
	static final double MIN_DRAIN_PER_SECOND = 1.0;

	static final long MIN_READY_TO_REPORT = 10;

	/** Se avisa del límite global cuando se usa esta fracción. */
	static final double LIMIT_WARNING_FRACTION = 0.9;

	private Diagnostics() {
	}

	/** Las conclusiones, de más a menos graves. */
	public static List<Finding> analyze(Snapshot snapshot) {
		List<Finding> findings = new ArrayList<>();
		broker(snapshot, findings);
		if (snapshot.broker().available()) {
			trace(snapshot, findings);
			snapshot.queues().forEach(queue -> queue(snapshot, queue, findings));
			snapshot.emitters().forEach(emitter -> emitter(emitter, findings));
			snapshot.consumers().forEach(consumer -> consumer(consumer, findings));
			load(snapshot, findings);
		}
		findings.sort(Comparator.comparing(Finding::severity));
		return findings;
	}

	private static void broker(Snapshot snapshot, List<Finding> findings) {
		if (!snapshot.broker().available()) {
			String reason = snapshot.broker().reason();
			// el motivo de RabbitMQ suele repetir «no está disponible»: solo se añade si dice algo más
			String detail = reason == null || reason.contains("no está disponible") ? "" : " (" + reason.replaceAll("\\.$", "") + ")";
			findings.add(new Finding(Severity.CRITICAL, Topic.STATE, "CAUSA: RabbitMQ no está disponible" + detail
					+ ", así que no se puede ver ni cambiar nada del laboratorio. SOLUCIÓN: comprueba que el contenedor "
					+ "de RabbitMQ está en marcha y pulsa «Volver a comprobar» en la pantalla «Estado»."));
		}
	}

	private static void trace(Snapshot snapshot, List<Finding> findings) {
		if (snapshot.trace().state() == Snapshot.Trace.State.DISABLED) {
			String how = snapshot.trace().hint() == null
					? "actívalo con «rabbitmqctl trace_on»" : snapshot.trace().hint();
			findings.add(new Finding(Severity.WARNING, Topic.TRACE,
					"CAUSA: el trazado de RabbitMQ está desactivado (tracing: false), y sin él la pestaña «Flujo» no puede "
							+ "mostrar mensajes. SOLUCIÓN: " + how));
		}
	}

	private static void queue(Snapshot snapshot, Queue queue, List<Finding> findings) {
		boolean isDeadLetter = snapshot.queues().stream().anyMatch(other -> queue.name().equals(other.deadLetterQueue()));
		if (isDeadLetter) {
			if (queue.ready() > 0) {
				String origins = String.join(", ", snapshot.queues().stream()
					.filter(other -> queue.name().equals(other.deadLetterQueue()))
					.map(other -> "«" + other.name() + "»")
					.toList());
				findings.add(new Finding(Severity.WARNING, Topic.QUEUES, "La dead-letter queue «" + queue.name() + "» tiene "
						+ queue.ready() + " mensajes listos: son mensajes que " + origins + " rechazó sin reencolar. "
						+ "Puedes verlos con «Inspeccionar» en la pestaña «Colas»."));
			}
		}
		else if (queue.consumers() == 0 && queue.ready() > 0) {
			findings.add(new Finding(Severity.WARNING, Topic.QUEUES, "CAUSA: la cola «" + queue.name()
					+ "» no se vacía porque NO TIENE NINGÚN CONSUMIDOR leyendo de ella; los mensajes que llegan se quedan "
					+ "esperando. Tiene " + queue.ready() + " mensajes listos. SOLUCIÓN: crea un consumidor sobre «"
					+ queue.name() + "» en la pestaña «Consumidores», o un consumidor simulado en «Generador»."));
		}
		if (queue.inPerSecond() != null && queue.outPerSecond() != null && !isDeadLetter) {
			double in = queue.inPerSecond();
			double out = queue.outPerSecond();
			if (in - out >= MIN_GROWTH_PER_SECOND && queue.consumers() != 0) {
				findings.add(new Finding(Severity.WARNING, Topic.QUEUES, "CAUSA: la cola «" + queue.name()
						+ "» crece porque entran " + number(in) + " mensajes por segundo y salen " + number(out)
						+ "; la diferencia son " + number(in - out) + " más cada segundo. Ahora tiene " + queue.ready()
						+ " mensajes listos. SOLUCIÓN: para o ralentiza algún emisor, o añade otro consumidor a esa cola."));
			}
			else if (out - in >= MIN_DRAIN_PER_SECOND && queue.ready() >= MIN_READY_TO_REPORT) {
				long seconds = Math.round(queue.ready() / (out - in));
				findings.add(new Finding(Severity.INFO, Topic.QUEUES, "La cola «" + queue.name()
						+ "» se está vaciando: salen " + number(out) + " mensajes por segundo y entran " + number(in)
						+ ". Tiene " + queue.ready() + " mensajes listos; a este ritmo estará vacía en unos " + seconds
						+ " segundos."));
			}
		}
	}

	private static void emitter(Emitter emitter, List<Finding> findings) {
		if (emitter.status() == Run.FAILED) {
			findings.add(new Finding(Severity.CRITICAL, Topic.EMITTERS, "CAUSA: el emisor «" + emitter.name()
					+ "» se paró por un error: " + reason(emitter.stopMessage()) + ". SOLUCIÓN: corrige la causa y vuelve a "
					+ "iniciarlo con «Iniciar» en la pestaña «Generador»."));
		}
		else if (emitter.status() == Run.FINISHED && emitter.stopMessage() != null) {
			findings.add(new Finding(Severity.INFO, Topic.EMITTERS,
					"El emisor «" + emitter.name() + "» terminó por sí solo: " + emitter.stopMessage()));
		}
		if (emitter.unrouted() > 0) {
			findings.add(new Finding(Severity.WARNING, Topic.EMITTERS, "CAUSA: " + emitter.unrouted() + " mensajes del emisor «"
					+ emitter.name() + "» no llegaron a ninguna cola y RabbitMQ los descartó. SOLUCIÓN: comprueba en la "
					+ "pestaña «Bindings» que el exchange tiene un binding cuyo patrón coincida con la routing key."));
		}
	}

	private static void consumer(Consumer consumer, List<Finding> findings) {
		if (consumer.status() == Run.FAILED) {
			findings.add(new Finding(Severity.CRITICAL, Topic.CONSUMERS, "CAUSA: el consumidor simulado «" + consumer.name()
					+ "» se paró por un error: " + reason(consumer.stopMessage()) + ". SOLUCIÓN: corrige la causa y "
					+ "vuelve a iniciarlo con «Iniciar» en la pestaña «Generador»."));
		}
	}

	private static void load(Snapshot snapshot, List<Finding> findings) {
		Snapshot.Load load = snapshot.load();
		if (load.maxRatePerSecond() > 0
				&& load.ratePerSecondInUse() >= load.maxRatePerSecond() * LIMIT_WARNING_FRACTION) {
			findings.add(new Finding(Severity.WARNING, Topic.EMITTERS,
					"El límite global de mensajes por segundo está casi alcanzado: los emisores en marcha usan "
							+ number(load.ratePerSecondInUse()) + " de " + load.maxRatePerSecond() + ". Para iniciar otro "
							+ "emisor, sube el límite en la pestaña «Generador» o para alguno."));
		}
		if (load.omittedPerSecond() > 0) {
			findings.add(new Finding(Severity.INFO, Topic.FLOW, "En la pestaña «Flujo» se omiten " + load.omittedPerSecond()
					+ " eventos por segundo en el registro y la animación por ser demasiados; los contadores y las "
					+ "gráficas siguen siendo exactos."));
		}
	}

	private static String reason(String message) {
		return message == null || message.isBlank() ? "no se indicó el motivo" : message;
	}

	/** Un número para leer: entero si lo es y, si no, con un decimal y coma. */
	public static String number(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.1f", value).replace('.', ',');
	}

}
