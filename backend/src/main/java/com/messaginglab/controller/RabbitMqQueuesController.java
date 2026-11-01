package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqQueuesApi;
import com.messaginglab.api.model.CreateQueueRequest;
import com.messaginglab.api.model.Queue;
import com.messaginglab.api.model.QueueCreationResponse;
import com.messaginglab.model.Creation;
import com.messaginglab.service.QueueService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa las operaciones sobre colas definidas en el contrato OpenAPI y traduce entre el contrato y
 * el modelo de dominio.
 */
@RestController
public class RabbitMqQueuesController implements RabbitMqQueuesApi {

	private final QueueService queueService;

	public RabbitMqQueuesController(QueueService queueService) {
		this.queueService = queueService;
	}

	@Override
	public ResponseEntity<List<Queue>> listQueues() {
		return ResponseEntity.ok(queueService.list().stream().map(RabbitMqQueuesController::toApi).toList());
	}

	@Override
	public ResponseEntity<QueueCreationResponse> createQueue(CreateQueueRequest request) {
		Creation<com.messaginglab.model.Queue> creation = queueService.create(request.getName(),
				request.getDeadLetterQueue());
		QueueCreationResponse body = new QueueCreationResponse(toApi(creation.value()), creation.created());
		return ResponseEntity.status(creation.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
	}

	@Override
	public ResponseEntity<Void> deleteQueue(String name) {
		queueService.delete(name);
		return ResponseEntity.noContent().build();
	}

	private static Queue toApi(com.messaginglab.model.Queue queue) {
		return new Queue(queue.name(), queue.ready(), queue.unacknowledged(), queue.total())
			.deadLetterQueue(queue.deadLetterQueue());
	}

}
