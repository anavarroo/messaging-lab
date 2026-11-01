package com.messaginglab.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Queue;
import com.messaginglab.service.QueueService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqQueuesController.class)
class RabbitMqQueuesControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private QueueService queueService;

	@Test
	void listsTheQueuesWithCountersAndDeadLetterQueue() throws Exception {
		given(queueService.list()).willReturn(List.of(new Queue("pedidos", "fallidos", 3, 2, 5),
				new Queue("fallidos", null, 0, 0, 0)));

		mockMvc.perform(get("/api/rabbitmq/queues"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].name").value("pedidos"))
			.andExpect(jsonPath("$[0].deadLetterQueue").value("fallidos"))
			.andExpect(jsonPath("$[0].ready").value(3))
			.andExpect(jsonPath("$[0].unacknowledged").value(2))
			.andExpect(jsonPath("$[0].total").value(5))
			.andExpect(jsonPath("$[1].name").value("fallidos"))
			.andExpect(jsonPath("$[1].deadLetterQueue").doesNotExist());
	}

	@Test
	void createsAQueueWithDeadLetterQueueAndAnswers201() throws Exception {
		given(queueService.create("pedidos", "fallidos"))
			.willReturn(new Creation<>(new Queue("pedidos", "fallidos", 0, 0, 0), true));

		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"deadLetterQueue\":\"fallidos\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.queue.name").value("pedidos"))
			.andExpect(jsonPath("$.queue.deadLetterQueue").value("fallidos"))
			.andExpect(jsonPath("$.queue.total").value(0));
	}

	@Test
	void createsAQueueWithoutDeadLetterQueue() throws Exception {
		given(queueService.create("pedidos", null))
			.willReturn(new Creation<>(new Queue("pedidos", null, 0, 0, 0), true));

		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\"}")).andExpect(status().isCreated());

		verify(queueService).create("pedidos", null);
	}

	@Test
	void anIdenticalExistingQueueAnswers200WithCreatedFalse() throws Exception {
		given(queueService.create("pedidos", null))
			.willReturn(new Creation<>(new Queue("pedidos", null, 4, 1, 5), false));

		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(false))
			.andExpect(jsonPath("$.queue.ready").value(4));
	}

	@Test
	void aValidationErrorAnswers400() throws Exception {
		given(queueService.create(any(), any()))
			.willThrow(new ValidationException("Una cola no puede ser su propia dead-letter queue."));

		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"a\",\"deadLetterQueue\":\"a\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("Una cola no puede ser su propia dead-letter queue."));
	}

	@Test
	void aConflictAnswers409() throws Exception {
		given(queueService.create(any(), any())).willThrow(new ConflictException(
				"Ya existe con ese nombre pero con otra configuración (difiere en la dead-letter queue)."));

		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"deadLetterQueue\":\"otra\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	void aMissingNameIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post("/api/rabbitmq/queues").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(queueService);
	}

	@Test
	void deletesAQueueWhoseNameHasSpecialCharacters() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/queues").param("name", "a/b%c ñ")).andExpect(status().isNoContent());

		verify(queueService).delete("a/b%c ñ");
	}

	@Test
	void deletingAMissingQueueAnswers404() throws Exception {
		Mockito.doThrow(new NotFoundException("La cola «x» no existe.")).when(queueService).delete("x");

		mockMvc.perform(delete("/api/rabbitmq/queues").param("name", "x"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("La cola «x» no existe."));
	}

	@Test
	void deletingWithoutANameIsRejected() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/queues"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Falta el parámetro «name»."));

		verifyNoInteractions(queueService);
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(queueService.list()).willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(get("/api/rabbitmq/queues"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

}
