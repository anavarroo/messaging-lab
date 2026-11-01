package com.messaginglab.generator.rabbitmq;

import com.messaginglab.generator.Interval;
import com.messaginglab.model.MessageAction;

/**
 * Lo que define un consumidor simulado.
 * @param processing lo que tarda en procesar cada mensaje
 * @param failurePercent porcentaje de mensajes que fallan, de 0 a 100
 * @param onFailure qué hace con un mensaje que falla
 */
public record SimulatedConsumerSpec(String name, String queue, Interval processing, int failurePercent,
		FailureAction onFailure) {

	/** Qué hace un consumidor simulado con un mensaje que falla. */
	public enum FailureAction {

		/** Rechazarlo y devolverlo a la cola. */
		REQUEUE(MessageAction.REQUEUE),

		/** Rechazarlo sin reencolar: va a la dead-letter queue de la cola, si la tiene. */
		REJECT(MessageAction.REJECT);

		private final MessageAction action;

		FailureAction(MessageAction action) {
			this.action = action;
		}

		MessageAction action() {
			return action;
		}

	}

}
