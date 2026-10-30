package com.messaginglab.controller;

import com.messaginglab.api.StatusApi;
import com.messaginglab.api.model.StatusResponse;
import com.messaginglab.model.ApplicationStatus;
import com.messaginglab.service.StatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la operación de estado definida en el contrato OpenAPI.
 */
@RestController
public class StatusController implements StatusApi {

	private final StatusService statusService;

	public StatusController(StatusService statusService) {
		this.statusService = statusService;
	}

	@Override
	public ResponseEntity<StatusResponse> getStatus() {
		return ResponseEntity.ok(toResponse(statusService.getStatus()));
	}

	private static StatusResponse toResponse(ApplicationStatus status) {
		return new StatusResponse(StatusResponse.StatusEnum.valueOf(status.status().name()), status.name(),
				status.version());
	}

}
