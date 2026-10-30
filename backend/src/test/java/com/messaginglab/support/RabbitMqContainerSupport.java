package com.messaginglab.support;

import com.messaginglab.gateway.rabbitmq.RabbitMqProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * RabbitMQ real compartido por todas las pruebas de integración (*IT) de una ejecución.
 * Se arranca una sola vez y lo detiene Testcontainers al terminar la JVM, así que cada prueba
 * debe usar nombres propios (por ejemplo, con un sufijo aleatorio) para no interferir con las demás.
 */
public final class RabbitMqContainerSupport {

	public static final String IMAGE = "rabbitmq:4.4-management";

	private static final RabbitMQContainer CONTAINER = new RabbitMQContainer(IMAGE);

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	static {
		CONTAINER.start();
	}

	private RabbitMqContainerSupport() {
	}

	public static String host() {
		return CONTAINER.getHost();
	}

	public static int amqpPort() {
		return CONTAINER.getAmqpPort();
	}

	public static int managementPort() {
		return CONTAINER.getHttpPort();
	}

	public static String username() {
		return CONTAINER.getAdminUsername();
	}

	public static String password() {
		return CONTAINER.getAdminPassword();
	}

	/** Propiedades de conexión con este contenedor, para construir los adaptadores reales en las pruebas. */
	public static RabbitMqProperties properties() {
		return new RabbitMqProperties(host(), amqpPort(), managementPort(), username(), password(), "/");
	}

	/** Petición POST con cuerpo JSON a la API de gestión, por ejemplo para publicar un mensaje de prueba. */
	public static HttpResponse<String> managementPost(String path, String json)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host() + ":" + managementPort() + path))
			.header("Authorization", "Basic " + basicCredentials())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Consulta GET a la API de gestión de RabbitMQ, para que las pruebas comprueben el estado real. */
	public static HttpResponse<String> managementGet(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host() + ":" + managementPort() + path))
			.header("Authorization", "Basic " + basicCredentials())
			.GET()
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String basicCredentials() {
		return Base64.getEncoder().encodeToString((username() + ":" + password()).getBytes());
	}

}
