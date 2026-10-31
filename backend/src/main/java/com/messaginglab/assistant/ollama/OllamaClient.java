package com.messaginglab.assistant.ollama;

import com.messaginglab.assistant.AssistantUnavailableException;
import com.messaginglab.assistant.Cancellation;
import com.messaginglab.assistant.LlmClient;
import com.messaginglab.assistant.LlmStatus;
import com.messaginglab.assistant.Message;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * El modelo de lenguaje servido por Ollama. Habla con su API HTTP: {@code GET /api/tags} para saber si el modelo está
 * descargado y {@code POST /api/chat}, que responde en streaming con una línea de JSON por cada trozo.
 */
public final class OllamaClient implements LlmClient {

	/** Cuánto se recuerda el estado, para no consultar a Ollama en cada pantalla que lo pide. */
	static final Duration STATUS_CACHE = Duration.ofSeconds(5);

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(3);

	/** Tiempo máximo hasta que el modelo empieza a responder (incluye cargarlo y leer el contexto). */
	private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(2);

	static final String UNAVAILABLE = "El modelo de IA no está disponible. Si has desactivado el modelo (OLLAMA_REPLICAS=0), "
			+ "el asistente no se puede usar; si no, comprueba que el contenedor «ollama» está en marcha con «docker compose ps».";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AssistantProperties properties;

	private final HttpClient http;

	private final Clock clock;

	private LlmStatus cachedStatus;

	private long cachedAt;

	public OllamaClient(AssistantProperties properties) {
		this(properties, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).build(),
				Clock.systemUTC());
	}

	OllamaClient(AssistantProperties properties, HttpClient http, Clock clock) {
		this.properties = properties;
		this.http = http;
		this.clock = clock;
	}

	@Override
	public synchronized LlmStatus status() {
		if (cachedStatus == null || clock.millis() - cachedAt > STATUS_CACHE.toMillis()) {
			cachedStatus = readStatus();
			cachedAt = clock.millis();
		}
		return cachedStatus;
	}

	private LlmStatus readStatus() {
		try {
			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(uri("/api/tags")).timeout(STATUS_TIMEOUT).GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				return new LlmStatus(LlmStatus.State.UNAVAILABLE, UNAVAILABLE);
			}
			return hasModel(response.body()) ? LlmStatus.ready() : new LlmStatus(LlmStatus.State.PREPARING, preparing());
		}
		catch (IOException | RuntimeException exception) {
			return new LlmStatus(LlmStatus.State.UNAVAILABLE, UNAVAILABLE);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new LlmStatus(LlmStatus.State.UNAVAILABLE, UNAVAILABLE);
		}
	}

	@SuppressWarnings("unchecked")
	private boolean hasModel(String body) {
		Object models = JSON.readValue(body, Map.class).get("models");
		if (!(models instanceof List<?> list)) {
			return false;
		}
		return list.stream().anyMatch(model -> model instanceof Map<?, ?> map && properties.model().equals(map.get("name")));
	}

	private String preparing() {
		return "El modelo «" + properties.model() + "» todavía no está descargado: la primera vez se descarga (unos 2 GB) y "
				+ "tarda unos minutos. Vuelve a probar en un rato.";
	}

	@Override
	public void chat(List<Message> messages, Consumer<String> onToken, Cancellation cancel) {
		if (cancel.isCancelled()) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(uri("/api/chat"))
			.timeout(RESPONSE_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body(messages)), StandardCharsets.UTF_8))
			.build();
		CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		// si se cancela mientras el modelo piensa o responde, se suelta lo que esté esperando
		cancel.onCancel(() -> pending.cancel(true));
		HttpResponse<InputStream> response;
		try {
			response = pending.get();
		}
		catch (CancellationException exception) {
			return;
		}
		catch (ExecutionException exception) {
			if (cancel.isCancelled()) {
				return;
			}
			throw new AssistantUnavailableException(UNAVAILABLE, exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return;
		}
		try (InputStream stream = response.body()) {
			cancel.onCancel(() -> closeQuietly(stream));
			if (response.statusCode() != 200) {
				throw new AssistantUnavailableException(response.statusCode() == 404 ? preparing()
						: "El modelo ha respondido con un error. Inténtalo de nuevo en un momento.");
			}
			read(stream, onToken, cancel);
		}
		catch (IOException exception) {
			if (!cancel.isCancelled()) {
				throw new AssistantUnavailableException("El modelo ha dejado de responder a mitad de la respuesta.", exception);
			}
		}
	}

	private void read(InputStream stream, Consumer<String> onToken, Cancellation cancel) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
		String line;
		while (!cancel.isCancelled() && (line = reader.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			Map<?, ?> chunk = JSON.readValue(line, Map.class);
			if (chunk.get("error") != null) {
				throw new AssistantUnavailableException("El modelo ha fallado al responder. Inténtalo de nuevo en un momento.");
			}
			if (chunk.get("message") instanceof Map<?, ?> message && message.get("content") instanceof String text && !text.isEmpty()) {
				onToken.accept(text);
			}
			if (Boolean.TRUE.equals(chunk.get("done"))) {
				return;
			}
		}
	}

	private Map<String, Object> body(List<Message> messages) {
		return Map.of("model", properties.model(), "stream", true,
				"messages", messages.stream().map(message -> Map.of("role", message.role().name().toLowerCase(), "content", message.text())).toList(),
				"options", Map.of("num_ctx", properties.contextTokens(), "num_predict", properties.maxResponseTokens(),
						"temperature", properties.temperature()));
	}

	private URI uri(String path) {
		return URI.create(properties.url().replaceAll("/+$", "") + path);
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		}
		catch (IOException exception) {
			// ya se está cancelando: no hay nada útil que hacer con el fallo
		}
	}

}
