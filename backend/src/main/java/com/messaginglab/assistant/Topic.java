package com.messaginglab.assistant;

/** De qué trata una pregunta o una conclusión; decide qué datos se le entregan al modelo. */
public enum Topic {

	/** Estado general: RabbitMQ y el backend. */
	STATE,
	QUEUES,
	EXCHANGES,
	EMITTERS,
	CONSUMERS,
	FLOW,
	TRACE,
	/** Una pregunta sobre qué es algo o cómo funciona. */
	CONCEPT,
	/** Una petición de hacer algo: el asistente no la hace, explica dónde se hace. */
	ACTION

}
