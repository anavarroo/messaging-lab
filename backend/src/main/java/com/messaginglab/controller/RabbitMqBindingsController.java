package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqBindingsApi;
import com.messaginglab.api.model.Binding;
import com.messaginglab.api.model.BindingCreationResponse;
import com.messaginglab.api.model.CreateBindingRequest;
import com.messaginglab.model.Creation;
import com.messaginglab.service.BindingService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa las operaciones sobre bindings definidas en el contrato OpenAPI y traduce entre el contrato
 * y el modelo de dominio.
 */
@RestController
public class RabbitMqBindingsController implements RabbitMqBindingsApi {

	private final BindingService bindingService;

	public RabbitMqBindingsController(BindingService bindingService) {
		this.bindingService = bindingService;
	}

	@Override
	public ResponseEntity<List<Binding>> listBindings(String exchange, String queue) {
		return ResponseEntity.ok(bindingService.list(exchange, queue).stream().map(RabbitMqBindingsController::toApi).toList());
	}

	@Override
	public ResponseEntity<BindingCreationResponse> createBinding(CreateBindingRequest request) {
		Creation<com.messaginglab.model.Binding> creation = bindingService.create(request.getExchange(),
				request.getQueue(), request.getRoutingKey());
		BindingCreationResponse body = new BindingCreationResponse(toApi(creation.value()), creation.created());
		return ResponseEntity.status(creation.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
	}

	@Override
	public ResponseEntity<Void> deleteBinding(String exchange, String queue, String routingKey) {
		bindingService.delete(exchange, queue, routingKey);
		return ResponseEntity.noContent().build();
	}

	private static Binding toApi(com.messaginglab.model.Binding binding) {
		return new Binding(binding.exchange(), binding.queue(), binding.routingKey());
	}

}
