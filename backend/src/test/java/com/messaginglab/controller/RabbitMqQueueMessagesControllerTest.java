package com.messaginglab.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.service.QueueInspectionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqQueueMessagesController.class)
class RabbitMqQueueMessagesControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private QueueInspectionService inspectionService;

	@Test
	void inspectsAQueueReturningItsMessages() throws Exception {
		given(inspectionService.inspect("cola")).willReturn(new QueueInspection(
				List.of(new QueueMessage("<b>hola</b>", "ex", "rk", true, null)), 1, false));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "cola"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.truncated").value(false))
			.andExpect(jsonPath("$.messages.length()").value(1))
			.andExpect(jsonPath("$.messages[0].body").value("<b>hola</b>"))
			.andExpect(jsonPath("$.messages[0].exchange").value("ex"))
			.andExpect(jsonPath("$.messages[0].routingKey").value("rk"))
			.andExpect(jsonPath("$.messages[0].redelivered").value(true))
			.andExpect(jsonPath("$.messages[0].deathInfo").doesNotExist());
	}

	@Test
	void showsTheOriginAndReasonOfADeadLetterMessage() throws Exception {
		given(inspectionService.inspect("dead")).willReturn(new QueueInspection(
				List.of(new QueueMessage("x", "ex", "rk", false, new DeathInfo("orig", "rejected"))), 1, false));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "dead"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.messages[0].deathInfo.originQueue").value("orig"))
			.andExpect(jsonPath("$.messages[0].deathInfo.reason").value("rejected"));
	}

	@Test
	void reportsATruncatedInspectionWithTheTotal() throws Exception {
		given(inspectionService.inspect("cola")).willReturn(new QueueInspection(List.of(), 51, true));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "cola"))
			.andExpect(jsonPath("$.total").value(51))
			.andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	void aQueueNameWithSpecialCharactersTravelsInTheQuery() throws Exception {
		given(inspectionService.inspect("a/b%c ñ")).willReturn(new QueueInspection(List.of(), 0, false));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "a/b%c ñ")).andExpect(status().isOk());

		verify(inspectionService).inspect("a/b%c ñ");
	}

	@Test
	void inspectingAMissingQueueAnswers404() throws Exception {
		given(inspectionService.inspect("x")).willThrow(new NotFoundException("La cola «x» no existe."));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "x"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("La cola «x» no existe."));
	}

	@Test
	void inspectingWithoutAQueueIsRejected() throws Exception {
		mockMvc.perform(get("/api/rabbitmq/queues/messages"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Falta el parámetro «queue»."));

		verifyNoInteractions(inspectionService);
	}

	@Test
	void purgesAQueueAndAnswers204() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/queues/messages").param("queue", "cola")).andExpect(status().isNoContent());

		verify(inspectionService).purge("cola");
	}

	@Test
	void purgingAMissingQueueAnswers404() throws Exception {
		Mockito.doThrow(new NotFoundException("La cola «x» no existe.")).when(inspectionService).purge("x");

		mockMvc.perform(delete("/api/rabbitmq/queues/messages").param("queue", "x")).andExpect(status().isNotFound());
	}

	@Test
	void purgingWithoutAQueueIsRejected() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/queues/messages")).andExpect(status().isBadRequest());

		verifyNoInteractions(inspectionService);
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(inspectionService.inspect("cola")).willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(get("/api/rabbitmq/queues/messages").param("queue", "cola"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

}
