package com.messaginglab.assistant.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.assistant.AssistantUnavailableException;
import com.messaginglab.assistant.Cancellation;
import com.messaginglab.assistant.LlmStatus;
import com.messaginglab.assistant.Message;
import com.messaginglab.assistant.Message.Role;
import com.messaginglab.support.MutableClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Contra un servidor HTTP falso que habla como Ollama. */
class OllamaClientTest {

	private static final String MODEL = "qwen2.5:3b";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));

	private HttpServer server;

	private OllamaClient client;

	private final AtomicReference<String> receivedBody = new AtomicReference<>();

	private volatile Responder chatResponse = exchange -> write(exchange, 200, line("Hola"), line(" mundo"), done());

	private volatile String tags = "{\"models\":[{\"name\":\"" + MODEL + "\"}]}";

	private volatile int tagsStatus = 200;

	private int requests;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/tags", exchange -> {
			requests++;
			write(exchange, tagsStatus, tags);
		});
		server.createContext("/api/chat", exchange -> {
			receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			chatResponse.respond(exchange);
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
		client = clientFor(server.getAddress().getPort());
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private OllamaClient clientFor(int port) {
		return new OllamaClient(new AssistantProperties("http://127.0.0.1:" + port, MODEL, 2048, 200, 0.2),
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), clock);
	}

	private interface Responder {

		void respond(HttpExchange exchange) throws IOException;

	}

	private static String line(String text) {
		return "{\"model\":\"qwen2.5:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"done\":false}\n";
	}

	private static String done() {
		return "{\"model\":\"qwen2.5:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}\n";
	}

	private static void write(HttpExchange exchange, int status, String... parts) throws IOException {
		exchange.sendResponseHeaders(status, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			for (String part : parts) {
				out.write(part.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		}
	}

	private List<String> chat(List<Message> messages) {
		List<String> tokens = new ArrayList<>();
		client.chat(messages, tokens::add, new Cancellation());
		return tokens;
	}

	private static final List<Message> CONVERSATION = List.of(new Message(Role.SYSTEM, "Instrucciones"),
			new Message(Role.USER, "Pregunta"));

	@Test
	void streamsTheAnswerChunkByChunk() {
		assertThat(chat(CONVERSATION)).containsExactly("Hola", " mundo");
	}

	@Test
	void sendsTheModelTheOptionsAndTheConversationWithOllamaRoles() {
		chat(List.of(new Message(Role.SYSTEM, "Instrucciones"), new Message(Role.USER, "¿Y esto?"), new Message(Role.ASSISTANT, "Eso."),
				new Message(Role.USER, "CONTEXTO\n\nPREGUNTA: ñ «x»")));

		var body = JsonMapper.builder().build().readTree(receivedBody.get());
		assertThat(body.get("model").asString()).isEqualTo(MODEL);
		assertThat(body.get("stream").asBoolean()).isTrue();
		assertThat(body.get("options").get("num_ctx").asInt()).isEqualTo(2048);
		assertThat(body.get("options").get("num_predict").asInt()).isEqualTo(200);
		assertThat(body.get("options").get("temperature").asDouble()).isEqualTo(0.2);
		assertThat(body.get("messages")).hasSize(4);
		assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
		assertThat(body.get("messages").get(2).get("role").asString()).isEqualTo("assistant");
		assertThat(body.get("messages").get(3).get("content").asString()).isEqualTo("CONTEXTO\n\nPREGUNTA: ñ «x»");
	}

	@Test
	void stopsAtTheLineThatSaysItIsDone() {
		chatResponse = exchange -> write(exchange, 200, line("a"), done(), line("nunca"));

		assertThat(chat(CONVERSATION)).containsExactly("a");
	}

	@Test
	void ignoresBlankLinesAndEmptyChunks() {
		chatResponse = exchange -> write(exchange, 200, "\n", line(""), line("x"), "\n", done());

		assertThat(chat(CONVERSATION)).containsExactly("x");
	}

	@Test
	void anErrorInTheStreamBecomesAnExplanationInSpanishAfterTheTokensAlreadyDelivered() {
		chatResponse = exchange -> write(exchange, 200, line("uno"), "{\"error\":\"model runner has unexpectedly stopped\"}\n");
		List<String> tokens = new ArrayList<>();

		assertThatThrownBy(() -> client.chat(CONVERSATION, tokens::add, new Cancellation())).isInstanceOf(AssistantUnavailableException.class)
			.hasMessage("El modelo ha fallado al responder. Inténtalo de nuevo en un momento.");
		assertThat(tokens).containsExactly("uno");
	}

	@Test
	void aConnectionCutInTheMiddleSaysSo() {
		chatResponse = exchange -> {
			exchange.sendResponseHeaders(200, 100_000);
			exchange.getResponseBody().write(line("uno").getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			exchange.close();
		};
		List<String> tokens = new ArrayList<>();

		assertThatThrownBy(() -> client.chat(CONVERSATION, tokens::add, new Cancellation())).isInstanceOf(AssistantUnavailableException.class)
			.hasMessage("El modelo ha dejado de responder a mitad de la respuesta.");
		assertThat(tokens).containsExactly("uno");
	}

	@Test
	void aModelThatIsNotThereYetIsExplainedAsBeingPrepared() {
		chatResponse = exchange -> write(exchange, 404, "{\"error\":\"model 'qwen2.5:3b' not found\"}");

		assertThatThrownBy(() -> chat(CONVERSATION)).isInstanceOf(AssistantUnavailableException.class)
			.hasMessageContaining("«qwen2.5:3b» todavía no está descargado").hasMessageContaining("2 GB");
	}

	@Test
	void anyOtherHttpErrorGetsAGenericMessageWithoutTechnicalDetails() {
		chatResponse = exchange -> write(exchange, 500, "{\"error\":\"traza interna secreta\"}");

		assertThatThrownBy(() -> chat(CONVERSATION)).isInstanceOf(AssistantUnavailableException.class)
			.hasMessage("El modelo ha respondido con un error. Inténtalo de nuevo en un momento.");
	}

	@Test
	void ollamaBeingDownIsExplainedInSpanish() {
		OllamaClient dead = clientFor(1);

		assertThatThrownBy(() -> dead.chat(CONVERSATION, token -> { }, new Cancellation())).isInstanceOf(AssistantUnavailableException.class)
			.hasMessageContaining("«ollama»");
	}

	@Test
	void cancellingWhileTheModelIsSilentReleasesTheCallRightAway() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		chatResponse = exchange -> {
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(line("uno").getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			started.countDown();
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException ignored) {
				// el servidor se detiene al terminar la prueba
			}
		};
		Cancellation cancel = new Cancellation();
		List<String> tokens = new ArrayList<>();
		Thread canceller = Thread.ofVirtual().start(() -> {
			try {
				started.await();
				Thread.sleep(200);
			}
			catch (InterruptedException ignored) {
				return;
			}
			cancel.cancel();
		});

		long start = System.nanoTime();
		client.chat(CONVERSATION, tokens::add, cancel);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
		assertThat(tokens).containsExactly("uno");
		canceller.join();
	}

	@Test
	void doesNotEvenCallTheModelIfItWasAlreadyCancelled() {
		Cancellation cancel = new Cancellation();
		cancel.cancel();
		receivedBody.set(null);

		client.chat(CONVERSATION, token -> { }, cancel);

		assertThat(receivedBody.get()).isNull();
	}

	@Test
	void isReadyWhenTheModelIsDownloaded() {
		assertThat(client.status()).isEqualTo(LlmStatus.ready());
	}

	@Test
	void isPreparingWhenTheModelIsNotDownloadedYet() {
		tags = "{\"models\":[{\"name\":\"otro:1b\"}]}";

		LlmStatus status = client.status();

		assertThat(status.state()).isEqualTo(LlmStatus.State.PREPARING);
		assertThat(status.message()).contains("«qwen2.5:3b»").contains("unos 2 GB");
	}

	@Test
	void isPreparingWithNoModelsAtAll() {
		tags = "{\"models\":[]}";

		assertThat(client.status().state()).isEqualTo(LlmStatus.State.PREPARING);
	}

	@Test
	void isUnavailableWhenOllamaDoesNotAnswerOrAnswersWithAnError() {
		assertThat(clientFor(1).status().state()).isEqualTo(LlmStatus.State.UNAVAILABLE);
		assertThat(clientFor(1).status().message()).contains("OLLAMA_REPLICAS=0");

		tagsStatus = 500;
		assertThat(client.status().state()).isEqualTo(LlmStatus.State.UNAVAILABLE);
	}

	@Test
	void remembersTheStatusForAFewSecondsAndThenAsksAgain() {
		client.status();
		client.status();
		assertThat(requests).isEqualTo(1);

		clock.advance(OllamaClient.STATUS_CACHE.plusSeconds(1));
		tags = "{\"models\":[]}";
		assertThat(client.status().state()).isEqualTo(LlmStatus.State.PREPARING);
		assertThat(requests).isEqualTo(2);
	}

	@Test
	void aTrailingSlashInTheUrlIsHarmless() {
		OllamaClient slash = new OllamaClient(new AssistantProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/", MODEL, 2048, 200, 0.2),
				HttpClient.newHttpClient(), clock);

		assertThat(slash.status()).isEqualTo(LlmStatus.ready());
	}

}
