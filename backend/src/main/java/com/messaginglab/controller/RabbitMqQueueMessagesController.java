package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqQueueMessagesApi;
import com.messaginglab.api.model.QueueInspection;
import com.messaginglab.service.QueueInspectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa la inspección y el vaciado de los mensajes de una cola definidos en el contrato OpenAPI.
 */
@RestController
public class RabbitMqQueueMessagesController implements RabbitMqQueueMessagesApi {

	private final QueueInspectionService inspectionService;

	public RabbitMqQueueMessagesController(QueueInspectionService inspectionService) {
		this.inspectionService = inspectionService;
	}

	@Override
	public ResponseEntity<QueueInspection> inspectQueue(String queue) {
		com.messaginglab.model.QueueInspection inspection = inspectionService.inspect(queue);
		return ResponseEntity.ok(new QueueInspection(inspection.messages().stream().map(ApiMappers::queueMessage).toList(),
				inspection.total(), inspection.truncated()));
	}

	@Override
	public ResponseEntity<Void> purgeQueue(String queue) {
		inspectionService.purge(queue);
		return ResponseEntity.noContent().build();
	}

}
