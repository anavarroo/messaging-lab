package com.messaginglab.gateway.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.control.ContainerRuntime.Container;
import com.messaginglab.exception.ControlUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contra un servidor HTTP falso que responde como el intermediario de Docker. */
class DockerProxyRuntimeTest {

	private HttpServer server;

	private final List<String> requests = new CopyOnWriteArrayList<>();

	/** Respuesta (código y cuerpo) para cada ruta, sin la consulta. */
	private final Map<String, Object[]> answers = new java.util.concurrent.ConcurrentHashMap<>();

	private DockerProxyRuntime runtime;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
					+ (query == null ? "" : "?" + URLDecoder.decode(query, StandardCharsets.UTF_8)));
			Object[] answer = answers.getOrDefault(exchange.getRequestURI().getPath(), new Object[] { 404, "" });
			byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders((int) answer[0], body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		runtime = new DockerProxyRuntime("http://127.0.0.1:" + server.getAddress().getPort() + "/",
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), () -> "abc123");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void findsItsOwnProjectFromTheLabelsOfItsContainerWithoutInspectingIt() {
		answers.put("/containers/json", new Object[] { 200,
				"[{\"Id\":\"abc123def\",\"Labels\":{\"com.docker.compose.project\":\"messaging-lab\",\"com.docker.compose.service\":\"backend\"}}]" });

		assertThat(runtime.ownProject()).contains("messaging-lab");
		assertThat(requests).containsExactly("GET /containers/json?all=true&filters={\"id\":[\"abc123\"]}");
	}

	@Test
	void outsideDockerThereIsNoProject() {
		answers.put("/containers/json", new Object[] { 200, "[]" });
		assertThat(runtime.ownProject()).isEmpty();
		assertThat(new DockerProxyRuntime("http://127.0.0.1:1", HttpClient.newHttpClient(), () -> null).ownProject()).isEmpty();
	}

	@Test
	void listsTheContainersOfTheProjectStoppedIncluded() {
		answers.put("/containers/json", new Object[] { 200, """
				[{"Id":"r1","State":"running","Labels":{"com.docker.compose.service":"rabbitmq"}},
				 {"Id":"a1","State":"exited","Labels":{"com.docker.compose.service":"assistant"}}]""" });

		assertThat(runtime.containers("messaging-lab")).containsExactly(new Container("r1", "rabbitmq", true),
				new Container("a1", "assistant", false));
		assertThat(requests).containsExactly(
				"GET /containers/json?all=true&filters={\"label\":[\"com.docker.compose.project=messaging-lab\"]}");
	}

	@Test
	void startsAndStopsWithPostAndAcceptsAlreadyDone() {
		answers.put("/containers/r1/start", new Object[] { 204, "" });
		answers.put("/containers/r1/stop", new Object[] { 304, "" });

		runtime.start("r1");
		runtime.stop("r1");

		assertThat(requests).containsExactly("POST /containers/r1/start", "POST /containers/r1/stop?t=20");
	}

	@Test
	void aRefusalOrNoAnswerIsExplainedInSpanish() {
		answers.put("/containers/r1/start", new Object[] { 403, "forbidden" });

		assertThatThrownBy(() -> runtime.start("r1")).isInstanceOf(ControlUnavailableException.class)
			.hasMessage(DockerProxyRuntime.FAILED);
		DockerProxyRuntime dead = new DockerProxyRuntime("http://127.0.0.1:1", HttpClient.newHttpClient(), () -> "x");
		assertThatThrownBy(dead::ownProject).isInstanceOf(ControlUnavailableException.class).hasMessageContaining("«docker-proxy»");
	}

}
