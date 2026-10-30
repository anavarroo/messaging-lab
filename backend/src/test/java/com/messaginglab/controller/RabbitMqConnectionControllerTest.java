package com.messaginglab.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.service.ConnectionStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqConnectionController.class)
class RabbitMqConnectionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConnectionStatusService connectionStatusService;

	@Test
	void returnsConnectedWithTheVersionWhenRabbitMqIsAvailable() throws Exception {
		given(connectionStatusService.status()).willReturn(new BrokerStatus("4.4.0"));

		mockMvc.perform(get("/api/rabbitmq/status"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value("CONNECTED"))
			.andExpect(jsonPath("$.version").value("4.4.0"));
	}

	@Test
	void returns503WithAnApiErrorWhenRabbitMqIsUnavailable() throws Exception {
		given(connectionStatusService.status())
			.willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(get("/api/rabbitmq/status"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("RabbitMQ no está disponible."));
	}

	@Test
	void returns504WhenRabbitMqDoesNotRespondInTime() throws Exception {
		given(connectionStatusService.status())
			.willThrow(new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", null));

		mockMvc.perform(get("/api/rabbitmq/status"))
			.andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.code").value("TIMEOUT"));
	}

	@Test
	void returns502WhenRabbitMqRejectsTheCredentials() throws Exception {
		given(connectionStatusService.status())
			.willThrow(new BrokerRejectedException("RabbitMQ ha rechazado las credenciales configuradas."));

		mockMvc.perform(get("/api/rabbitmq/status"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("BROKER_REJECTED"))
			.andExpect(jsonPath("$.message").value("RabbitMQ ha rechazado las credenciales configuradas."));
	}

}
