package com.messaginglab.flow;

import java.util.function.Consumer;

/** Fuente de eventos de un sistema de mensajería (el trazado de RabbitMQ, en el futuro un consumidor de Kafka). */
public interface FlowSource {

	/** Estado actual; no debe bloquear mucho, porque se consulta cada segundo mientras hay pantallas abiertas. */
	FlowSourceStatus status();

	/** Empieza a enviar eventos a {@code sink}. Solo se llama con la fuente activa. */
	void start(Consumer<FlowEvent> sink);

	void stop();

}
