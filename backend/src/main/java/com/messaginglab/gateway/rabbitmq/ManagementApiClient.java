package com.messaginglab.gateway.rabbitmq;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente de la API HTTP de gestión de RabbitMQ.
 * <p>
 * Los segmentos de ruta variables (virtual host y nombres) deben pasarse como variables de la URI, no
 * concatenados, para que se codifiquen y admitan caracteres como {@code /} o {@code %}.
 * Los fallos se traducen a excepciones de dominio con {@link BrokerErrorMapper}.
 */
@Component
class ManagementApiClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient client;

	private final String virtualHost;

	@Autowired
	ManagementApiClient(RabbitMqProperties properties) {
		this(RestClient.builder().requestFactory(requestFactory()), properties);
	}

	ManagementApiClient(RestClient.Builder builder, RabbitMqProperties properties) {
		this.client = builder.baseUrl("http://" + properties.host() + ":" + properties.managementPort() + "/api")
			.defaultHeaders(headers -> headers.setBasicAuth(properties.username(), properties.password()))
			.build();
		this.virtualHost = properties.virtualHost();
	}

	private static ClientHttpRequestFactory requestFactory() {
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(READ_TIMEOUT);
		return factory;
	}

	/** Virtual host sin codificar; se pasa como variable de la URI. */
	String virtualHost() {
		return virtualHost;
	}

	<T> T get(String uri, ParameterizedTypeReference<T> type, Object... uriVariables) {
		try {
			return client.get().uri(uri, uriVariables).retrieve().body(type);
		}
		catch (RuntimeException exception) {
			throw BrokerErrorMapper.map(exception);
		}
	}

	/** Crea o actualiza un recurso; devuelve el estado HTTP (201 si lo crea, 204 si ya existía igual). */
	HttpStatusCode put(String uri, Object body, Object... uriVariables) {
		try {
			return client.put()
				.uri(uri, uriVariables)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toBodilessEntity()
				.getStatusCode();
		}
		catch (RuntimeException exception) {
			throw BrokerErrorMapper.map(exception);
		}
	}

	HttpStatusCode post(String uri, Object body, Object... uriVariables) {
		try {
			return client.post()
				.uri(uri, uriVariables)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.toBodilessEntity()
				.getStatusCode();
		}
		catch (RuntimeException exception) {
			throw BrokerErrorMapper.map(exception);
		}
	}

	<T> T post(String uri, Object body, ParameterizedTypeReference<T> type, Object... uriVariables) {
		try {
			return client.post()
				.uri(uri, uriVariables)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(type);
		}
		catch (RuntimeException exception) {
			throw BrokerErrorMapper.map(exception);
		}
	}

	void delete(String uri, Object... uriVariables) {
		try {
			client.delete().uri(uri, uriVariables).retrieve().toBodilessEntity();
		}
		catch (RuntimeException exception) {
			throw BrokerErrorMapper.map(exception);
		}
	}

}
