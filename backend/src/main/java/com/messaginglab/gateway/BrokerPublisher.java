package com.messaginglab.gateway;

import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;

/** Publicación de mensajes en el sistema de mensajería. */
public interface BrokerPublisher {

	/** Publica el mensaje de forma persistente y espera la confirmación del sistema de mensajería. */
	PublishResult publish(PublishRequest request);

}
