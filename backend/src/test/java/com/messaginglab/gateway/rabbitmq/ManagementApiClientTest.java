package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.LabException;
import com.messaginglab.exception.NotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class ManagementApiClientTest {

	private static final String BASE = "http://rabbit.test:15672/api";

	private static final String PASSWORD = "secreto-de-prueba";

	private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
	};

	private MockRestServiceServer server;

	private ManagementApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new ManagementApiClient(builder,
				new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", PASSWORD, "/"));
	}

	@Test
	void sendsBasicAuthenticationAndReturnsTheBody() {
		String credentials = Base64.getEncoder().encodeToString(("lab:" + PASSWORD).getBytes());
		server.expect(requestTo(BASE + "/overview"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", "Basic " + credentials))
			.andRespond(withSuccess("{\"rabbitmq_version\":\"4.4.0\"}", MediaType.APPLICATION_JSON));

		Map<String, Object> overview = client.get("/overview", MAP);

		assertThat(overview).containsEntry("rabbitmq_version", "4.4.0");
		server.verify();
	}

	@Test
	void encodesTheVirtualHostAndNamesWithSpecialCharacters() {
		server.expect(requestTo(BASE + "/exchanges/%2F/a%2Fb%25c%20%C3%B1%23"))
			.andExpect(method(HttpMethod.PUT))
			.andRespond(withStatus(HttpStatus.CREATED));

		client.put("/exchanges/{vhost}/{name}", Map.of("type", "topic"), client.virtualHost(), "a/b%c ñ#");

		server.verify();
	}

	@Test
	void sendsJsonBodies() {
		server.expect(requestTo(BASE + "/queues/%2F/cola"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"durable\":true}"))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.put("/queues/{vhost}/{name}", Map.of("durable", true), client.virtualHost(), "cola");

		server.verify();
	}

	@Test
	void putDistinguishesCreatedFromAlreadyExisting() {
		server.expect(requestTo(BASE + "/queues/%2F/a")).andRespond(withStatus(HttpStatus.CREATED));
		server.expect(requestTo(BASE + "/queues/%2F/a")).andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThat(client.put("/queues/{vhost}/{name}", Map.of(), "/", "a").value()).isEqualTo(201);
		assertThat(client.put("/queues/{vhost}/{name}", Map.of(), "/", "a").value()).isEqualTo(204);
	}

	@Test
	void postReadsTheResponseBody() {
		server.expect(requestTo(BASE + "/queues/%2F/q/get"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

		Map<String, Object> result = client.post("/queues/{vhost}/{name}/get", Map.of("count", 1), MAP, "/", "q");

		assertThat(result).containsEntry("ok", true);
	}

	@Test
	void deleteSendsADeleteRequest() {
		server.expect(requestTo(BASE + "/exchanges/%2F/ex"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.delete("/exchanges/{vhost}/{name}", "/", "ex");

		server.verify();
	}

	@Test
	void rejectedCredentialsDoNotRevealThem() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN }) {
			server.reset();
			server.expect(requestTo(BASE + "/overview"))
				.andRespond(withStatus(status).body("{\"error\":\"not_authorised\"}").contentType(MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerRejectedException.class)
				.hasMessage("RabbitMQ ha rechazado las credenciales configuradas.")
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(PASSWORD).doesNotContain("lab"));
		}
	}

	@Test
	void notFoundIsTranslated() {
		server.expect(requestTo(BASE + "/queues/%2F/x")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.get("/queues/{vhost}/{name}", MAP, "/", "x"))
			.isInstanceOf(NotFoundException.class)
			.hasMessage("El elemento indicado no existe en RabbitMQ.");
	}

	@Test
	void anInequivalentTypeIsAConflictThatNamesTheDifference() {
		server.expect(requestTo(BASE + "/exchanges/%2F/ex"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":\"bad_request\",\"reason\":\"inequivalent arg 'type' for exchange 'ex' in vhost '/': "
						+ "received 'direct' but current is 'topic'\"}"));

		assertThatThrownBy(() -> client.put("/exchanges/{vhost}/{name}", Map.of(), "/", "ex"))
			.isInstanceOf(ConflictException.class)
			.hasMessage("Ya existe con ese nombre pero con otra configuración (difiere en el tipo).")
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain("vhost", "received"));
	}

	@Test
	void anInequivalentDeadLetterArgumentIsAConflict() {
		server.expect(requestTo(BASE + "/queues/%2F/q"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"reason\":\"inequivalent arg 'x-dead-letter-exchange' for queue 'q' in vhost '/'\"}"));

		assertThatThrownBy(() -> client.put("/queues/{vhost}/{name}", Map.of(), "/", "q"))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("difiere en la dead-letter queue");
	}

	@Test
	void anyOtherBadRequestIsARejection() {
		server.expect(requestTo(BASE + "/queues/%2F/q"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":\"bad_request\",\"reason\":\"algo interno\"}"));

		assertThatThrownBy(() -> client.put("/queues/{vhost}/{name}", Map.of(), "/", "q"))
			.isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ ha rechazado la operación.");
	}

	@Test
	void gatewayErrorsMeanUnavailable() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
				HttpStatus.GATEWAY_TIMEOUT }) {
			server.reset();
			server.expect(requestTo(BASE + "/overview")).andRespond(withStatus(status));

			assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerUnavailableException.class)
				.hasMessage("RabbitMQ no está disponible.");
		}
	}

	@Test
	void otherServerErrorsAreARejection() {
		server.expect(requestTo(BASE + "/overview")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ no ha podido completar la operación.");
	}

	@Test
	void connectionFailuresMeanUnavailable() {
		for (IOException failure : new IOException[] { new ConnectException("Connection refused"),
				new UnknownHostException("rabbit.test"), new HttpConnectTimeoutException("connect timed out") }) {
			server.reset();
			server.expect(requestTo(BASE + "/overview")).andRespond(failing(failure));

			assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerUnavailableException.class)
				.hasMessage("RabbitMQ no está disponible.");
		}
	}

	@Test
	void slowResponsesMeanTimeout() {
		for (IOException failure : new IOException[] { new SocketTimeoutException("Read timed out"),
				new HttpTimeoutException("request timed out") }) {
			server.reset();
			server.expect(requestTo(BASE + "/overview")).andRespond(failing(failure));

			assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerTimeoutException.class)
				.hasMessage("RabbitMQ no ha respondido a tiempo.");
		}
	}

	@Test
	void anUnreadableResponseIsARejection() {
		server.expect(requestTo(BASE + "/overview"))
			.andRespond(withSuccess("esto no es json", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ ha devuelto una respuesta que no se puede interpretar.");
	}

	@Test
	void noErrorMessageRevealsTheCredentialsOrTechnicalDetails() {
		server.expect(requestTo(BASE + "/overview")).andRespond(failing(new ConnectException("Connection refused")));

		assertThatThrownBy(() -> client.get("/overview", MAP)).isInstanceOf(LabException.class)
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain(PASSWORD, "refused", "Exception", "rabbit.test"));
	}

	private static ResponseCreator failing(IOException failure) {
		return request -> {
			throw failure;
		};
	}

}
