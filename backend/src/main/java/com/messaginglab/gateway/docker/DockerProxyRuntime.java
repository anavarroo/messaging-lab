package com.messaginglab.gateway.docker;

import com.messaginglab.control.ContainerRuntime;
import com.messaginglab.exception.ControlUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La API de Docker a través del intermediario ({@code docker-proxy} del Compose), que solo deja listar, arrancar y
 * parar contenedores (ver infra/docker-proxy/haproxy.cfg.template). El backend nunca ve el socket de Docker.
 */
final class DockerProxyRuntime implements ContainerRuntime {

	static final String PROJECT_LABEL = "com.docker.compose.project";

	static final String SERVICE_LABEL = "com.docker.compose.service";

	static final String FAILED = "No se ha podido hablar con Docker para parar o levantar el servicio. Comprueba que el "
			+ "contenedor «docker-proxy» está en marcha con «docker compose ps».";

	/** Lo que Docker espera a que un contenedor se pare antes de forzarlo. */
	private static final int STOP_SECONDS = 20;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String url;

	private final HttpClient http;

	/** El nombre de máquina del backend: en Docker, el identificador corto de su contenedor. */
	private final Supplier<String> hostname;

	DockerProxyRuntime(String url, HttpClient http, Supplier<String> hostname) {
		this.url = url.replaceAll("/+$", "");
		this.http = http;
		this.hostname = hostname;
	}

	/** Se busca en la lista filtrando por su id: el intermediario no deja inspeccionar contenedores. */
	@Override
	public Optional<String> ownProject() {
		String host = hostname.get();
		if (host == null || host.isBlank()) {
			return Optional.empty();
		}
		return list(Map.of("id", List.of(host))).valueStream()
			.map(node -> node.path("Labels").path(PROJECT_LABEL).stringValue(null))
			.filter(project -> project != null)
			.findFirst();
	}

	@Override
	public List<Container> containers(String project) {
		return list(Map.of("label", List.of(PROJECT_LABEL + "=" + project))).valueStream()
			.map(node -> new Container(node.path("Id").asString(), node.path("Labels").path(SERVICE_LABEL).asString(""),
					"running".equals(node.path("State").asString())))
			.toList();
	}

	private JsonNode list(Map<String, List<String>> filters) {
		return read(ok(send(get("/containers/json?all=true&filters=" + encode(JSON.writeValueAsString(filters))))));
	}

	@Override
	public void start(String id) {
		post("/containers/" + encode(id) + "/start", Duration.ofSeconds(30));
	}

	@Override
	public void stop(String id) {
		post("/containers/" + encode(id) + "/stop?t=" + STOP_SECONDS, Duration.ofSeconds(STOP_SECONDS + 15));
	}

	private void post(String path, Duration timeout) {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url + path))
			.timeout(timeout)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
		// 304: ya estaba arrancado o parado
		if (response.statusCode() != 204 && response.statusCode() != 304) {
			throw new ControlUnavailableException(FAILED);
		}
	}

	private HttpRequest get(String path) {
		return HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5)).GET().build();
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException exception) {
			throw new ControlUnavailableException(FAILED, exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ControlUnavailableException(FAILED, exception);
		}
	}

	private static HttpResponse<String> ok(HttpResponse<String> response) {
		if (response.statusCode() != 200) {
			throw new ControlUnavailableException(FAILED);
		}
		return response;
	}

	private static JsonNode read(HttpResponse<String> response) {
		try {
			return JSON.readTree(response.body());
		}
		catch (RuntimeException exception) {
			throw new ControlUnavailableException(FAILED, exception);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
