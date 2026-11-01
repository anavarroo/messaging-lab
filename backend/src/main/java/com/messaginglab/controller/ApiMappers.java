package com.messaginglab.controller;

import com.messaginglab.api.model.Consumer;
import com.messaginglab.api.model.DeathInfo;
import com.messaginglab.api.model.QueueMessage;
import com.messaginglab.model.ConsumerInfo;

/** Traducciones del modelo de dominio a los modelos del contrato que comparten varios controladores. */
final class ApiMappers {

	private ApiMappers() {
	}

	static QueueMessage queueMessage(com.messaginglab.model.QueueMessage message) {
		DeathInfo death = message.deathInfo() == null ? null
				: new DeathInfo(message.deathInfo().originQueue(), message.deathInfo().reason());
		return new QueueMessage(message.body(), message.exchange(), message.routingKey(), message.redelivered())
			.deathInfo(death);
	}

	static Consumer consumer(ConsumerInfo consumer) {
		return new Consumer(consumer.id(), consumer.name(), consumer.queue(),
				com.messaginglab.api.model.AckMode.valueOf(consumer.ackMode().name()));
	}

}
