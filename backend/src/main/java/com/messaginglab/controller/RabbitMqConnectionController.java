package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqConnectionApi;
import com.messaginglab.api.model.RabbitMqStatusResponse;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.service.ConnectionStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la consulta de la conexión con RabbitMQ definida en el contrato OpenAPI. Si RabbitMQ no
 * está disponible, la excepción de dominio se traduce a {@code ApiError} en {@link ApiExceptionHandler}.
 */
@RestController
public class RabbitMqConnectionController implements RabbitMqConnectionApi {

	private final ConnectionStatusService connectionStatusService;

	public RabbitMqConnectionController(ConnectionStatusService connectionStatusService) {
		this.connectionStatusService = connectionStatusService;
	}

	@Override
	public ResponseEntity<RabbitMqStatusResponse> getRabbitMqStatus() {
		BrokerStatus status = connectionStatusService.status();
		return ResponseEntity.ok(new RabbitMqStatusResponse(RabbitMqStatusResponse.StatusEnum.CONNECTED, status.version()));
	}

}
