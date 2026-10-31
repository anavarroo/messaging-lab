package com.messaginglab.assistant;

import com.messaginglab.assistant.Finding.Severity;
import com.messaginglab.assistant.KnowledgeBase.Card;
import com.messaginglab.assistant.Snapshot.Consumer;
import com.messaginglab.assistant.Snapshot.Emitter;
import com.messaginglab.assistant.Snapshot.Exchange;
import com.messaginglab.assistant.Snapshot.Queue;
import com.messaginglab.assistant.Snapshot.Run;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prepara lo que el modelo va a leer: primero las conclusiones ya sacadas, después los datos relevantes para la
 * pregunta, ya resumidos, y por último las fichas del manual que encajen. Todo en un texto corto que cabe en un
 * presupuesto fijo, sin cuerpos de mensajes.
 */
public final class ContextBuilder {

	/** Cuántas conclusiones, como máximo, entran en el contexto. */
	static final int MAX_FINDINGS = 6;

	private static final int MAX_CARDS_FOR_CONCEPTS = 2;

	private final KnowledgeBase manual;

	public ContextBuilder(KnowledgeBase manual) {
		this.manual = manual;
	}

	/** Estimación prudente de los tokens de un texto en español (con nombres y símbolos, unos 3 caracteres por token). */
	public static int tokens(String text) {
		return (text.length() + 2) / 3;
	}

	/** El contexto para esta pregunta, de como mucho {@code budgetTokens} tokens. */
	public String build(Snapshot snapshot, Set<Topic> topics, String question, int budgetTokens) {
		Lines lines = new Lines(budgetTokens);
		List<Finding> findings = Diagnostics.analyze(snapshot);
		String asked = Words.normalize(question == null ? "" : question);

		if (topics.contains(Topic.ACTION)) {
			return action(findings, lines);
		}
		lines.add("CONCLUSIONES:");
		List<Finding> relevant = findings.stream()
			.filter(finding -> finding.severity() == Severity.CRITICAL || topics.contains(finding.topic()))
			.limit(MAX_FINDINGS)
			.toList();
		if (relevant.isEmpty()) {
			lines.add("- No se ha detectado ningún problema en lo que preguntas.");
		}

		relevant.forEach(finding -> lines.add("- " + finding.text()));

		lines.add("DATOS (a las " + snapshot.time().toString().substring(11, 19) + " UTC):");
		if (topics.contains(Topic.STATE) || !snapshot.broker().available()) {
			state(snapshot, lines);
		}
		if (topics.contains(Topic.TRACE)) {
			trace(snapshot, lines);
		}
		if (topics.contains(Topic.QUEUES)) {
			queues(snapshot, relevant, asked, lines);
		}
		if (topics.contains(Topic.EXCHANGES)) {
			exchanges(snapshot, asked, lines);
		}
		if (topics.contains(Topic.EMITTERS)) {
			emitters(snapshot, asked, lines);
		}
		if (topics.contains(Topic.CONSUMERS)) {
			consumers(snapshot, asked, lines);
		}
		if (topics.contains(Topic.FLOW)) {
			flow(snapshot, lines);
		}

		List<Card> cards = manual.find(question, topics.contains(Topic.CONCEPT) ? MAX_CARDS_FOR_CONCEPTS : 1);
		if (!cards.isEmpty()) {
			lines.add("MANUAL:");
			cards.forEach(card -> lines.add("- «" + card.title() + "»: " + card.text()));
		}
		return lines.text();
	}

	/**
	 * Una petición de cambio: el asistente no la hace, así que solo necesita saber dónde se hace. Los datos del
	 * laboratorio sobran y distraerían al modelo; solo se añade lo que impide usarlo.
	 */
	private String action(List<Finding> findings, Lines lines) {
		lines.add("PETICIÓN: el usuario pide hacer un cambio. NO PUEDES HACERLO: dile dónde se hace.");
		lines.add("MANUAL:");
		Card actions = manual.card(KnowledgeBase.ACTIONS);
		lines.add("- «" + actions.title() + "»: " + actions.text());
		findings.stream().filter(finding -> finding.severity() == Severity.CRITICAL).forEach(finding -> lines.add("- " + finding.text()));
		return lines.text();
	}

	private static void state(Snapshot snapshot, Lines lines) {
		Snapshot.Broker broker = snapshot.broker();
		lines.add(broker.available() ? "- RabbitMQ: levantado (versión " + broker.version() + ")"
				: "- RabbitMQ: no disponible");
		lines.add("- Hay " + snapshot.queues().size() + " colas, " + snapshot.exchanges().size() + " exchanges, "
				+ snapshot.emitters().size() + " emisores y " + snapshot.consumers().size() + " consumidores simulados.");
	}

	private static void trace(Snapshot snapshot, Lines lines) {
		lines.add("- Trazado de RabbitMQ: " + switch (snapshot.trace().state()) {
			case ACTIVE -> "activo (tracing: true)";
			case DISABLED -> "desactivado (tracing: false)";
			case UNKNOWN -> "no se puede comprobar";
		});
	}

	/** Las colas de las que se habla primero, después las que tienen conclusiones y por último las más llenas. */
	private static void queues(Snapshot snapshot, List<Finding> findings, String asked, Lines lines) {
		String conclusions = Words.normalize(findings.stream().map(Finding::text).collect(Collectors.joining(" ")));
		List<Queue> ordered = new ArrayList<>(snapshot.queues());
		ordered.sort(Comparator.<Queue>comparingInt(queue -> mentioned(asked, queue.name()) ? 0 : 1)
			.thenComparingInt(queue -> mentioned(conclusions, queue.name()) ? 0 : 1)
			.thenComparing(Comparator.comparingLong(Queue::ready).reversed()));
		int written = 0;
		for (Queue queue : ordered) {
			StringBuilder line = new StringBuilder("- cola «" + queue.name() + "»: " + queue.ready() + " listos, "
					+ queue.unacknowledged() + " pendientes de confirmar");
			if (queue.consumers() >= 0) {
				line.append(", ").append(queue.consumers()).append(queue.consumers() == 1 ? " consumidor" : " consumidores");
			}
			if (queue.inPerSecond() != null && queue.outPerSecond() != null) {
				line.append(", entran ").append(Diagnostics.number(queue.inPerSecond())).append("/s y salen ")
					.append(Diagnostics.number(queue.outPerSecond())).append("/s");
			}
			if (queue.deadLetterQueue() != null) {
				line.append(", su dead-letter queue es «").append(queue.deadLetterQueue()).append("»");
			}
			if (!lines.add(line.toString())) {
				break;
			}
			written++;
		}
		if (written < ordered.size()) {
			lines.addAlways("- (y " + (ordered.size() - written) + " colas más, sin detalle por falta de espacio)");
		}
	}

	private static void exchanges(Snapshot snapshot, String asked, Lines lines) {
		List<Exchange> ordered = new ArrayList<>(snapshot.exchanges());
		ordered.sort(Comparator.comparingInt(exchange -> mentioned(asked, exchange.name()) ? 0 : 1));
		int written = 0;
		for (Exchange exchange : ordered) {
			String bound = exchange.boundQueues().isEmpty() ? "sin bindings"
					: "enlazado a " + exchange.boundQueues().stream().map(name -> "«" + name + "»").collect(Collectors.joining(", "));
			if (!lines.add("- exchange «" + exchange.name() + "» (" + exchange.type() + "): " + bound)) {
				break;
			}
			written++;
		}
		if (written < ordered.size()) {
			lines.addAlways("- (y " + (ordered.size() - written) + " exchanges más)");
		}
	}

	private static void emitters(Snapshot snapshot, String asked, Lines lines) {
		long running = snapshot.emitters().stream().filter(emitter -> emitter.status() == Run.RUNNING).count();
		lines.add("- Emisores en marcha: " + running + " de " + snapshot.emitters().size() + ". Suman "
				+ Diagnostics.number(snapshot.load().ratePerSecondInUse()) + " mensajes por segundo (límite "
				+ snapshot.load().maxRatePerSecond() + ").");
		List<Emitter> ordered = new ArrayList<>(snapshot.emitters());
		ordered.sort(Comparator.comparingInt(emitter -> mentioned(asked, emitter.name()) ? 0 : 1));
		for (Emitter emitter : ordered) {
			if (!lines.add("- emisor «" + emitter.name() + "»: " + status(emitter.status()) + ", publica en " + emitter.destination()
					+ ", " + Diagnostics.number(emitter.ratePerSecond()) + " por segundo, " + emitter.published()
					+ " publicados, " + emitter.unrouted() + " sin enrutar")) {
				break;
			}
		}
	}

	private static void consumers(Snapshot snapshot, String asked, Lines lines) {
		List<Consumer> ordered = new ArrayList<>(snapshot.consumers());
		ordered.sort(Comparator.comparingInt(consumer -> mentioned(asked, consumer.name()) ? 0 : 1));
		if (ordered.isEmpty()) {
			lines.add("- No hay consumidores simulados.");
		}
		for (Consumer consumer : ordered) {
			if (!lines.add("- consumidor simulado «" + consumer.name() + "»: " + status(consumer.status()) + ", lee de la cola «"
					+ consumer.queue() + "», " + Diagnostics.number(consumer.ratePerSecond()) + " por segundo, "
					+ consumer.processed() + " procesados, " + consumer.failed() + " fallidos")) {
				break;
			}
		}
	}

	private static void flow(Snapshot snapshot, Lines lines) {
		lines.add("- Flujo: los emisores en marcha suman " + Diagnostics.number(snapshot.load().ratePerSecondInUse())
				+ " mensajes por segundo; se omiten " + snapshot.load().omittedPerSecond() + " eventos por segundo en la animación.");
	}

	private static String status(Run run) {
		return switch (run) {
			case RUNNING -> "en marcha";
			case PAUSED -> "en pausa";
			case STOPPED -> "parado";
			case FINISHED -> "terminado";
			case FAILED -> "fallado";
		};
	}

	private static boolean mentioned(String normalizedText, String name) {
		return normalizedText.contains(Words.normalize(name));
	}

	/** Las líneas del contexto, que dejan de añadirse cuando se agota el presupuesto. */
	private static final class Lines {

		private final StringBuilder text = new StringBuilder();

		private int remaining;

		Lines(int budgetTokens) {
			this.remaining = budgetTokens;
		}

		/** Añade la línea si cabe; si no, devuelve {@code false} y no añade nada. */
		boolean add(String line) {
			int cost = tokens(line) + 1;
			if (cost > remaining) {
				return false;
			}
			remaining -= cost;
			text.append(line).append('\n');
			return true;
		}

		/** Añade una línea de aviso aunque justo no quepa, con el margen mínimo de una línea corta. */
		void addAlways(String line) {
			remaining = Math.max(remaining - tokens(line) - 1, 0);
			text.append(line).append('\n');
		}

		String text() {
			return text.toString().stripTrailing();
		}

	}

}
