package com.messaginglab.diagnostics;

/** De qué parte del laboratorio trata una conclusión. */
public enum Topic {

	/** Estado general: RabbitMQ y el backend. */
	STATE,
	QUEUES,
	EXCHANGES,
	EMITTERS,
	CONSUMERS,
	FLOW,
	TRACE

}
