package com.messaginglab.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Binding;
import com.messaginglab.model.Creation;
import com.messaginglab.service.BindingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqBindingsController.class)
class RabbitMqBindingsControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BindingService bindingService;

	@Test
	void listsAllTheBindingsWhenThereAreNoFilters() throws Exception {
		given(bindingService.list(null, null)).willReturn(List.of(new Binding("temas", "cola1", "a.*"),
				new Binding("difusion", "cola2", "")));

		mockMvc.perform(get("/api/rabbitmq/bindings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].exchange").value("temas"))
			.andExpect(jsonPath("$[0].queue").value("cola1"))
			.andExpect(jsonPath("$[0].routingKey").value("a.*"))
			.andExpect(jsonPath("$[1].routingKey").value(""));
	}

	@Test
	void passesTheFiltersToTheService() throws Exception {
		given(bindingService.list("a/b", "c d")).willReturn(List.of());

		mockMvc.perform(get("/api/rabbitmq/bindings").param("exchange", "a/b").param("queue", "c d"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		verify(bindingService).list("a/b", "c d");
	}

	@Test
	void createsABindingAndAnswers201() throws Exception {
		given(bindingService.create("temas", "cola1", "a.*"))
			.willReturn(new Creation<>(new Binding("temas", "cola1", "a.*"), true));

		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"queue\":\"cola1\",\"routingKey\":\"a.*\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.binding.exchange").value("temas"))
			.andExpect(jsonPath("$.binding.queue").value("cola1"))
			.andExpect(jsonPath("$.binding.routingKey").value("a.*"));
	}

	@Test
	void aMissingRoutingKeyIsPassedAsNull() throws Exception {
		given(bindingService.create("difusion", "cola1", null))
			.willReturn(new Creation<>(new Binding("difusion", "cola1", ""), true));

		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"difusion\",\"queue\":\"cola1\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.binding.routingKey").value(""));
	}

	@Test
	void anExistingBindingAnswers200WithCreatedFalse() throws Exception {
		given(bindingService.create("temas", "cola1", "a"))
			.willReturn(new Creation<>(new Binding("temas", "cola1", "a"), false));

		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"queue\":\"cola1\",\"routingKey\":\"a\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.created").value(false));
	}

	@Test
	void aValidationErrorAnswers400() throws Exception {
		given(bindingService.create(any(), any(), any()))
			.willThrow(new ValidationException("El patrón no puede tener palabras vacías."));

		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"queue\":\"cola1\",\"routingKey\":\"a..b\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("El patrón no puede tener palabras vacías."));
	}

	@Test
	void aMissingExchangeOrQueueAnswers404() throws Exception {
		given(bindingService.create(any(), any(), any())).willThrow(new NotFoundException("La cola «x» no existe."));

		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\",\"queue\":\"x\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void aMissingQueueFieldIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post("/api/rabbitmq/bindings").contentType(MediaType.APPLICATION_JSON)
			.content("{\"exchange\":\"temas\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(bindingService);
	}

	@Test
	void deletesABindingWithAPatternAsRoutingKey() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/bindings").param("exchange", "temas")
			.param("queue", "cola1")
			.param("routingKey", "a.#")).andExpect(status().isNoContent());

		verify(bindingService).delete("temas", "cola1", "a.#");
	}

	@Test
	void deletesABindingWithoutRoutingKey() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/bindings").param("exchange", "temas").param("queue", "cola1"))
			.andExpect(status().isNoContent());

		verify(bindingService).delete("temas", "cola1", null);
	}

	@Test
	void deletingAMissingBindingAnswers404() throws Exception {
		Mockito.doThrow(new NotFoundException("El binding indicado no existe."))
			.when(bindingService)
			.delete("temas", "cola1", null);

		mockMvc.perform(delete("/api/rabbitmq/bindings").param("exchange", "temas").param("queue", "cola1"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("El binding indicado no existe."));
	}

	@Test
	void deletingWithoutAQueueIsRejected() throws Exception {
		mockMvc.perform(delete("/api/rabbitmq/bindings").param("exchange", "temas"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Falta el parámetro «queue»."));

		verifyNoInteractions(bindingService);
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(bindingService.list(null, null)).willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(get("/api/rabbitmq/bindings"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

}
