package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqPublicationsApi;
import com.messaginglab.api.model.PublishRequest;
import com.messaginglab.api.model.PublishResponse;
import com.messaginglab.model.PublishResult;
import com.messaginglab.service.PublishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la publicación de mensajes definida en el contrato OpenAPI. Un mensaje aceptado pero no
 * enrutado no es un error: responde 200 con {@code routed=false}.
 */
@RestController
public class RabbitMqPublicationsController implements RabbitMqPublicationsApi {

	private final PublishService publishService;

	public RabbitMqPublicationsController(PublishService publishService) {
		this.publishService = publishService;
	}

	@Override
	public ResponseEntity<PublishResponse> publishMessage(PublishRequest request) {
		PublishResult result = publishService.publish(request.getExchange(), request.getRoutingKey(),
				request.getBody());
		return ResponseEntity.ok(new PublishResponse(result.routed()));
	}

}
