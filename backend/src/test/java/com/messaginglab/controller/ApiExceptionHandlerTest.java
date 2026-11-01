package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
		.setControllerAdvice(new ApiExceptionHandler())
		.build();

	@Test
	void validationIs400() throws Exception {
		expectError("/validation", 400, "VALIDATION", "El nombre no es válido.");
	}

	@Test
	void notFoundIs404() throws Exception {
		expectError("/not-found", 404, "NOT_FOUND", "La cola no existe.");
	}

	@Test
	void conflictIs409() throws Exception {
		expectError("/conflict", 409, "CONFLICT", "Ya existe con otro tipo.");
	}

	@Test
	void brokerUnavailableIs503() throws Exception {
		expectError("/unavailable", 503, "BROKER_UNAVAILABLE", "RabbitMQ no está disponible.");
	}

	@Test
	void brokerTimeoutIs504() throws Exception {
		expectError("/timeout", 504, "TIMEOUT", "RabbitMQ no ha respondido a tiempo.");
	}

	@Test
	void brokerRejectedIs502() throws Exception {
		expectError("/rejected", 502, "BROKER_REJECTED", "RabbitMQ ha rechazado la operación.");
	}

	@Test
	void missingParameterIs400() throws Exception {
		expectError("/param", 400, "VALIDATION", "Falta el parámetro «name».");
	}

	@Test
	void invalidParameterTypeIs400() throws Exception {
		expectError("/tag/abc", 400, "VALIDATION", "El valor del parámetro «tag» no es válido.");
	}

	@Test
	void unreadableBodyIs400() throws Exception {
		mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("El cuerpo de la petición no es válido."));
	}

	@Test
	void invalidBodyNamesTheField() throws Exception {
		mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value("El campo «name» no es válido."));
	}

	@Test
	void theResponseNeverExposesTechnicalDetails() throws Exception {
		String body = mockMvc.perform(get("/unavailable")).andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("Exception", "at com.", "stackTrace", "trace");
	}

	private void expectError(String path, int status, String code, String message) throws Exception {
		mockMvc.perform(get(path))
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message));
	}

	record Body(@NotBlank String name) {
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/validation")
		void validation() {
			throw new ValidationException("El nombre no es válido.");
		}

		@GetMapping("/not-found")
		void notFound() {
			throw new NotFoundException("La cola no existe.");
		}

		@GetMapping("/conflict")
		void conflict() {
			throw new ConflictException("Ya existe con otro tipo.");
		}

		@GetMapping("/unavailable")
		void unavailable() {
			throw new BrokerUnavailableException("RabbitMQ no está disponible.",
					new java.net.ConnectException("detalle técnico interno"));
		}

		@GetMapping("/timeout")
		void timeout() {
			throw new BrokerTimeoutException("RabbitMQ no ha respondido a tiempo.", new RuntimeException("x"));
		}

		@GetMapping("/rejected")
		void rejected() {
			throw new BrokerRejectedException("RabbitMQ ha rechazado la operación.");
		}

		@GetMapping("/param")
		void param(@RequestParam String name) {
		}

		@GetMapping("/tag/{tag}")
		void tag(@PathVariable long tag) {
		}

		@PostMapping("/body")
		void body(@Valid @RequestBody Body body) {
		}

	}

}
