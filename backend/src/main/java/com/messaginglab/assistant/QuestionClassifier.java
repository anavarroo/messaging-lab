package com.messaginglab.assistant;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decide de qué trata una pregunta, para entregarle al modelo solo los datos que hacen falta. Es deliberadamente
 * simple (palabras clave): determinista, explicable y sin gastar memoria del modelo.
 */
public final class QuestionClassifier {

	/** Fragmentos de palabra, ya sin tildes, que indican cada tema. */
	private static final Map<Topic, String[]> KEYWORDS = new LinkedHashMap<>();

	static {
		KEYWORDS.put(Topic.TRACE, new String[] { "traz", "tracing", "trace", "firehose" });
		KEYWORDS.put(Topic.FLOW, new String[] { "flujo", "pasando", "trafico", "actividad", "moviendo", "fluyendo", "tiempo real", "diagrama", "animacion", "ritmo", "por segundo", "omit",
				"grafica", "punto" });
		KEYWORDS.put(Topic.QUEUES, new String[] { "cola", "listos", "acumul", "crece", "vaci", "pendiente", "dead-letter",
				"dead letter", "dlq", "fallidos", "atasc" });
		KEYWORDS.put(Topic.EXCHANGES, new String[] { "exchange", "binding", "routing", "enrut", "topic", "fanout", "direct",
				"patron" });
		KEYWORDS.put(Topic.EMITTERS, new String[] { "emisor", "enrut", "emite", "emiti", "generador", "publica", "plantilla", "limite",
				"sensor", "robot" });
		KEYWORDS.put(Topic.CONSUMERS, new String[] { "consumidor", "consume", "consumir", "confirm", "rechaz", "ack",
				"reencol", "reentreg" });
		KEYWORDS.put(Topic.STATE, new String[] { "estado", "funciona", "levantado", "caido", "disponible", "conectado",
				"version", "backend", "rabbitmq esta", "no responde", "modelo" });
		KEYWORDS.put(Topic.CONCEPT, new String[] { "que es", "que son", "significa", "para que sirve", "como funciona",
				"explica", "diferencia", "que hace", "defin" });
	}

	/** Los temas de una pregunta general («¿está todo bien?»): un resumen de todo. */
	public static final Set<Topic> GENERAL = Set.copyOf(EnumSet.of(Topic.STATE, Topic.QUEUES, Topic.EMITTERS, Topic.CONSUMERS));

	/** Preguntas por el estado general («¿está todo bien?», «¿hay algún problema?»), que se responden con el resumen. */
	private static final Pattern STATUS_CHECK = Pattern.compile(
			"todo bien|todo ok|todo correcto|algun problema|hay problemas|algo mal|algo raro|que pasa|que ocurre|como va|como esta|resumen|estado del laboratorio");

	/** Preguntas por si ahora mismo se mueven mensajes («¿hay algo pasando datos?», «¿hay tráfico?»). */
	private static final Pattern ACTIVITY_CHECK = Pattern.compile(
			"pasando|trafico|actividad|moviendo|fluyendo|se mueve|circulando|algo funcionando|hay flujo|algo en marcha|esta publicando|se esta publicando");

	/** ¿Pregunta si ahora mismo pasan mensajes? */
	public boolean isActivityCheck(String question) {
		return question != null && ACTIVITY_CHECK.matcher(Words.normalize(question)).find();
	}

	/** ¿Pregunta por cómo está el laboratorio en general? */
	public boolean isStatusCheck(String question) {
		return question != null && STATUS_CHECK.matcher(Words.normalize(question)).find();
	}

	/** Una petición de acción: un verbo de cambio al principio, o tras «puedes», «podrías» o «quiero que». */
	private static final Pattern ACTION = Pattern.compile(
			"(^|puedes |podrias |quiero que |hazme |por favor )(para(?! que)|parar|pares|pausa|deten|detengas|borra|borrar|borres|elimina|eliminar|elimines|crea|crear|crees|vacia|vaciar|vacies|inicia|iniciar|inicies|arranca|publica|publicar|publiques|cambia|cambiar|cambies|sube|baja|reinicia|activa|actives|desactiva|desactives)\\b");

	/** Los temas de la pregunta; si no encaja en ninguno, un resumen general. */
	public Set<Topic> classify(String question) {
		String text = Words.normalize(question == null ? "" : question);
		Set<Topic> topics = EnumSet.noneOf(Topic.class);
		KEYWORDS.forEach((topic, words) -> {
			for (String word : words) {
				if (text.contains(word)) {
					topics.add(topic);
					break;
				}
			}
		});
		if (ACTION.matcher(text.replaceAll("^[¿¡\\s]+", "")).find()) {
			topics.add(Topic.ACTION);
		}
		// el flujo se alimenta del trazado: si algo falla ahí, es lo primero que hay que mirar
		if (topics.contains(Topic.FLOW)) {
			topics.add(Topic.TRACE);
		}
		if (topics.isEmpty()) {
			topics.addAll(GENERAL);
		}
		return topics;
	}

}
