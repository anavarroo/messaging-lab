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
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.service.ExchangeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqExchangesController.class)
class RabbitMqExchangesControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ExchangeService exchangeService;

	@Test
	void listsTheExchangesMarkingThePredefinedOnes() throws Exception {
		given(exchangeService.list()).willReturn(List.of(new Exchange("pedidos", ExchangeType.TOPIC, false),
				new Exchange("amq.direct", ExchangeType.DIRECT, true)));

		mockMvc.perform(get("/api/rabbitmq/exchanges"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].name").value("pedidos"))
			.andExpect(jsonPath("$[0].type").value("TOPIC"))
			.andExpect(jsonPath("$[0].predefined").value(false))
			.andExpect(jsonPath("$[1].name").value("amq.direct"))
			.andExpect(jsonPath("$[1].predefined").value(true));
	}

	@Test
	void createsAnExchangeAndAnswers201() throws Exception {
		given(exchangeService.create("pedidos", ExchangeType.TOPIC))
			.willReturn(new Creation<>(new Exchange("pedidos", ExchangeType.TOPIC, false), true));

		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"type\":\"TOPIC\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.exchange.name").value("pedidos"))
			.andExpect(jsonPath("$.exchange.type").value("TOPIC"))
			.andExpect(jsonPath("$.exchange.predefined").value(false));
	}

	@Test
	void anIdenticalExistingExchangeAnswers200WithCreatedFalse() throws Exception {
		given(exchangeService.create("pedidos", ExchangeType.DIRECT))
			.willReturn(new Creation<>(new Exchange("pedidos", ExchangeType.DIRECT, false), false));

		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"type\":\"DIRECT\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(false));
	}

	@Test
	void aValidationErrorAnswers400() throws Exception {
		given(exchangeService.create(any(), any()))
			.willThrow(new ValidationException("El nombre del exchange no puede estar vacío."));

		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\",\"type\":\"DIRECT\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("El nombre del exchange no puede estar vacío."));
	}

	@Test
	void aConflictAnswers409() throws Exception {
		given(exchangeService.create(any(), any())).willThrow(new ConflictException(
				"Ya existe con ese nombre pero con otra configuración (difiere en el tipo)."));

		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"type\":\"DIRECT\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	void anUnsupportedTypeIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\",\"type\":\"HEADERS\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(exchangeService);
	}

	@Test
	void aMissingTypeIsRejectedNamingTheField() throws Exception {
		mockMvc.perform(post("/api/rabbitmq/exchanges").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"pedidos\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("El campo «type» no es válido."));

		verifyNoInteractions(exchangeService);
	}

	@Test
	void deletesAnExchangeWhoseNameHasSpecialCharacters() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/exchanges").param("name", "a/b%c ñ")).andExpect(status().isNoContent());

		verify(exchangeService).delete("a/b%c ñ");
	}

	@Test
	void deletingAMissingExchangeAnswers404() throws Exception {
		Mockito.doThrow(new NotFoundException("El exchange «x» no existe.")).when(exchangeService).delete("x");

		mockMvc.perform(delete("/api/rabbitmq/exchanges").param("name", "x"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("El exchange «x» no existe."));
	}

	@Test
	void deletingWithoutANameIsRejected() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/exchanges"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Falta el parámetro «name»."));

		verifyNoInteractions(exchangeService);
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(exchangeService.list()).willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(get("/api/rabbitmq/exchanges"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

}
