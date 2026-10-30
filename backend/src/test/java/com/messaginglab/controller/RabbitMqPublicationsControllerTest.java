package com.messaginglab.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.PublishResult;
import com.messaginglab.service.PublishService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqPublicationsController.class)
class RabbitMqPublicationsControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PublishService publishService;

	@Test
	void aRoutedMessageAnswers200WithRoutedTrue() throws Exception {
		given(publishService.publish("temas", "a.b", "hola")).willReturn(new PublishResult(true));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"routingKey\":\"a.b\",\"body\":\"hola\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.routed").value(true));
	}

	@Test
	void aMessageThatNoQueueReceivedAnswers200WithRoutedFalse() throws Exception {
		given(publishService.publish("temas", "a.b", "hola")).willReturn(new PublishResult(false));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"routingKey\":\"a.b\",\"body\":\"hola\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.routed").value(false));
	}

	@Test
	void theDefaultExchangeAndAMissingRoutingKeyArePassedToTheService() throws Exception {
		given(publishService.publish("", null, "x")).willReturn(new PublishResult(false));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"\",\"body\":\"x\"}")).andExpect(status().isOk());

		verify(publishService).publish("", null, "x");
	}

	@Test
	void theBodyIsPassedAsLiteralText() throws Exception {
		given(publishService.publish(any(), any(), any())).willReturn(new PublishResult(true));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"e\",\"body\":\"<b>ñ</b> \\\"q\\\"\\n\"}")).andExpect(status().isOk());

		verify(publishService).publish("e", null, "<b>ñ</b> \"q\"\n");
	}

	@Test
	void anInvalidBodyAnswers400WithTheMessage() throws Exception {
		given(publishService.publish(any(), any(), any()))
			.willThrow(new ValidationException("El cuerpo del mensaje no puede estar vacío."));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"body\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("El cuerpo del mensaje no puede estar vacío."));
	}

	@Test
	void aMissingBodyFieldIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(publishService);
	}

	@Test
	void aMissingExchangeAnswers404() throws Exception {
		given(publishService.publish(any(), any(), any())).willThrow(new NotFoundException("El exchange «x» no existe."));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"x\",\"body\":\"hola\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(publishService.publish(any(), any(), any()))
			.willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"e\",\"body\":\"hola\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("RabbitMQ no está disponible."));
	}

	@Test
	void aTimeoutAnswers504() throws Exception {
		given(publishService.publish(any(), any(), any()))
			.willThrow(new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", new RuntimeException()));

		mockMvc.perform(post("/api/rabbitmq/publications").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"e\",\"body\":\"hola\"}"))
			.andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.code").value("TIMEOUT"));
	}

}
