package com.messaginglab.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * El manual de la aplicación: fichas breves de cada concepto, con las palabras del laboratorio y la pestaña donde se ve
 * cada cosa. Cada ficha es corta a propósito, porque cabe en un contexto pequeño.
 */
public final class KnowledgeBase {

	/** @param keywords fragmentos de palabra, sin tildes, con los que se encuentra la ficha */
	public record Card(String id, String title, List<String> keywords, String text) {

	}

	/** Las fichas, en el orden en que un mensaje se las encuentra. */
	public static final List<Card> CARDS = List.of(
			card("mensaje", "Mensaje", "Un mensaje es un trozo de información que una aplicación envía a otra. Lleva un contenido y una etiqueta (la routing key). Se publica desde la pestaña «Publicación» y lo genera solo un emisor en «Generador».",
					"mensaje"),
			card("productor", "Productor", "El productor es quien publica los mensajes. En el laboratorio publicas tú desde «Publicación», o lo hacen los emisores del «Generador».",
					"productor", "publicador"),
			card("exchange", "Exchange", "Un exchange recibe los mensajes del productor y decide a qué colas mandarlos, según sus bindings. No guarda nada. Hay tres tipos: direct, fanout y topic. Se crean en la pestaña «Exchanges».",
					"exchange"),
			card("cola", "Cola", "Una cola guarda los mensajes, en orden, hasta que un consumidor los procesa. Sus contadores (listos, pendientes de confirmar, total) se ven en la pestaña «Colas».",
					"cola", "buzon"),
			card("binding", "Binding", "Un binding es la regla que une un exchange con una cola: dice qué mensajes (por su routing key o su patrón) van a esa cola. Sin bindings, un exchange no entrega a ninguna cola. Se crean en la pestaña «Bindings».",
					"binding", "enlace"),
			card("routing-key", "Routing key", "La routing key es la etiqueta que lleva un mensaje. El exchange la compara con los bindings para decidir a qué colas va. En un exchange topic admite los comodines «*» (una palabra) y «#» (varias).",
					"routing", "etiqueta", "comodin", "patron"),
			card("consumidor", "Consumidor", "Un consumidor recibe los mensajes de una cola. Hay dos tipos en el laboratorio: los de sesión, que controlas tú mensaje a mensaje en «Consumidores», y los simulados, que consumen solos y se crean en «Generador».",
					"consumidor", "consumir", "consume"),
			card("acknowledgement", "Confirmación (acknowledgement)", "Cuando un consumidor termina con un mensaje, le responde a RabbitMQ: confirmar (el mensaje se elimina), rechazar y reencolar (vuelve a la cola) o rechazar sin reencolar (va a la dead-letter queue, si la cola tiene una, y si no se descarta).",
					"acknowledg", "confirm", "ack", "rechaz", "reencol"),
			card("dead-letter", "Dead-letter queue", "Una dead-letter queue es una cola especial que recibe los mensajes que otra cola rechaza sin reencolar. Se elige al crear la cola, en la pestaña «Colas». Se pueden ver con «Inspeccionar».",
					"dead-letter", "dead letter", "dlq", "fallidos"),
			card("exchange-defecto", "Exchange por defecto", "El exchange por defecto es un exchange sin nombre que existe siempre. Entrega el mensaje a la cola cuyo nombre coincide con la routing key. Por él pasan las publicaciones «directamente a una cola».",
					"por defecto", "sin nombre"),
			card("tipos-exchange", "Tipos de exchange", "Direct: entrega a las colas cuyo binding coincide exactamente con la routing key. Fanout: entrega a todas las colas enlazadas, sin mirar la routing key. Topic: entrega según un patrón con comodines.",
					"direct", "fanout", "topic", "tipo"),
			card("sin-enrutar", "Mensaje sin enrutar", "Un mensaje sin enrutar es el que no llega a ninguna cola porque ningún binding coincide. RabbitMQ lo descarta. El laboratorio lo avisa al publicar y el generador lo cuenta como «sin enrutar».",
					"enrutar", "descart", "no llega"),
			card("reentregado", "Mensaje reentregado", "Un mensaje reentregado es el que RabbitMQ entrega por segunda vez, porque se rechazó y reencoló o porque el consumidor se fue sin confirmarlo. También puede marcarse al inspeccionar una cola.",
					"reentreg"),
			card("pendiente", "Mensaje pendiente de confirmar", "Un mensaje pendiente de confirmar es el que un consumidor ya ha recibido pero todavía no ha confirmado ni rechazado. No se ve al inspeccionar la cola; el contador «pendientes» los cuenta.",
					"pendiente", "unacked"),
			card("trazado", "Trazado", "El trazado (tracing o firehose) es una función de RabbitMQ que copia cada publicación y cada entrega a un exchange interno. La pestaña «Flujo» lo usa para mostrar los mensajes. Si está desactivado (tracing: false), «Flujo» no muestra nada. Se activa con «rabbitmqctl trace_on» y duplica los mensajes dentro de RabbitMQ, así que es solo para laboratorio.",
					"traz", "tracing", "firehose", "trace"),
			card("flujo", "Pestaña Flujo", "La pestaña «Flujo» muestra en tiempo real cómo viaja cada mensaje: publicador, exchange, cola y consumidor, con el ritmo de cada uno. Solo dibuja lo que ha tenido mensajes en los últimos 10 segundos. Tiene registro de eventos, filtro, pausa, cámara lenta y gráficas.",
					"flujo", "tiempo real", "diagrama", "animacion"),
			card("generador", "Generador de tráfico", "El generador (pestaña «Generador») publica mensajes sin parar, como un sensor o un robot, a la frecuencia elegida. Corre en el servidor y sigue aunque cierres el navegador. «Parar todo» detiene todo.",
					"generador", "emisor", "emite", "plantilla"),
			card("limite", "Límite global del generador", "El generador tiene un límite global de mensajes por segundo (100 por defecto, 500 como máximo absoluto), que se cambia en la pestaña «Generador». No se puede iniciar un emisor si la suma superaría el límite.",
					"limite", "maximo"),
			card("acciones", "Dónde se hace cada cosa", "El asistente no puede hacer cambios. Parar todos los emisores y consumidores simulados: botón «Parar todo» en «Generador». Iniciar, pausar o parar un emisor: su tarjeta en «Generador». Crear o eliminar colas, y vaciarlas: «Colas». Exchanges: «Exchanges». Bindings: «Bindings». Publicar: «Publicación». Consumir mensaje a mensaje: «Consumidores».",
					"accion", "donde se hace"),
			card("simulado", "Consumidor simulado", "Un consumidor simulado lee de una cola solo, tardando lo que se le indica en procesar cada mensaje y fallando un porcentaje de las veces. Los que fallan se reencolan o van a la dead-letter queue. Sirve para ver cómo se vacía una cola.",
					"simulado", "fallo"));

	private static Card card(String id, String title, String text, String... keywords) {
		return new Card(id, title, List.of(keywords), text);
	}

	public static final String ACTIONS = "acciones";

	public Card card(String id) {
		return CARDS.stream().filter(card -> card.id().equals(id)).findFirst().orElseThrow();
	}

	/** Las fichas que mejor encajan con la pregunta, como mucho {@code max}; ninguna si no encaja ninguna. */
	public List<Card> find(String question, int max) {
		String text = Words.normalize(question == null ? "" : question);
		List<Scored> scored = new ArrayList<>();
		for (Card card : CARDS) {
			int score = (int) card.keywords().stream().filter(text::contains).count();
			if (score > 0) {
				scored.add(new Scored(card, score));
			}
		}
		scored.sort(Comparator.comparingInt(Scored::score).reversed());
		return scored.stream().limit(max).map(Scored::card).toList();
	}

	private record Scored(Card card, int score) {

	}

}
