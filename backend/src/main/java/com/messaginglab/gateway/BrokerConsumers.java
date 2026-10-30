package com.messaginglab.gateway;

import com.messaginglab.model.AckMode;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.StopReason;

/** Consumo de mensajes de una cola. */
public interface BrokerConsumers {

	/**
	 * Empieza a consumir la cola.
	 * <p>
	 * En modo {@link AckMode#MANUAL} no se entrega un mensaje hasta que se resuelve el anterior.
	 */
	ConsumerHandle start(String queue, AckMode ackMode, ConsumerListener listener);

	/** Consumidor en marcha. */
	interface ConsumerHandle {

		/** Aplica la acción sobre un mensaje pendiente de confirmar. */
		void resolve(long deliveryTag, MessageAction action);

		/** Detiene el consumidor; los mensajes pendientes de confirmar vuelven a la cola. */
		void stop();

	}

	/** Receptor de lo que ocurre en un consumidor. */
	interface ConsumerListener {

		void onMessage(DeliveredMessage message);

		/** El consumidor ha terminado por una causa ajena al usuario: cola eliminada o conexión perdida. */
		void onStopped(StopReason reason);

	}

}
