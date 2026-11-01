package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqConsumersApi;
import com.messaginglab.api.model.ActionOutcome;
import com.messaginglab.api.model.Consumer;
import com.messaginglab.api.model.CreateConsumerRequest;
import com.messaginglab.api.model.ResolveMessageRequest;
import com.messaginglab.api.model.ResolveMessageResponse;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.MessageAction;
import com.messaginglab.service.ConsumerSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa el alta y baja de consumidores y la resolución de sus mensajes, definidas en el contrato
 * OpenAPI. Los mensajes llegan al navegador por el flujo de eventos de la sesión, no por aquí.
 */
@RestController
public class RabbitMqConsumersController implements RabbitMqConsumersApi {

	private final ConsumerSessionService sessions;

	public RabbitMqConsumersController(ConsumerSessionService sessions) {
		this.sessions = sessions;
	}

	@Override
	public ResponseEntity<Consumer> createConsumer(String sessionId, CreateConsumerRequest request) {
		ConsumerInfo consumer = sessions.startConsumer(sessionId, request.getName(), request.getQueue(),
				AckMode.valueOf(request.getAckMode().name()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiMappers.consumer(consumer));
	}

	@Override
	public ResponseEntity<Void> deleteConsumer(String sessionId, String consumerId) {
		sessions.stopConsumer(sessionId, consumerId);
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<ResolveMessageResponse> resolveMessage(String sessionId, String consumerId, Long deliveryTag,
			ResolveMessageRequest request) {
		com.messaginglab.model.ActionOutcome outcome = sessions.resolve(sessionId, consumerId, deliveryTag,
				MessageAction.valueOf(request.getAction().name()));
		return ResponseEntity.ok(new ResolveMessageResponse(ActionOutcome.valueOf(outcome.name())));
	}

}
