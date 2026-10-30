package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.messaginglab.support.RabbitMqContainerSupport;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extremo a extremo por HTTP: la aplicación completa en un puerto real, un RabbitMQ real y un cliente que
 * lee el flujo SSE como lo haría el navegador.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsumersIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@LocalServerPort
	private int port;

	private final List<SseClient> streams = new ArrayList<>();

	private final List<String> queues = new ArrayList<>();

	@DynamicPropertySource
	static void rabbitMq(DynamicPropertyRegistry registry) {
		registry.add("messaginglab.rabbitmq.host", RabbitMqContainerSupport::host);
		registry.add("messaginglab.rabbitmq.port", RabbitMqContainerSupport::amqpPort);
		registry.add("messaginglab.rabbitmq.management-port", RabbitMqContainerSupport::managementPort);
		registry.add("messaginglab.rabbitmq.username", RabbitMqContainerSupport::username);
		registry.add("messaginglab.rabbitmq.password", RabbitMqContainerSupport::password);
		registry.add("messaginglab.rabbitmq.virtual-host", () -> "/");
		// el servidor se entera de que el navegador se ha ido al fallar un latido: se acorta para no esperar 30 s
		registry.add("messaginglab.session.heartbeat-interval", () -> "1s");
	}

	@AfterEach
	void cleanUp() throws Exception {
		streams.forEach(SseClient::close);
		for (String queue : queues) {
			request("DELETE", "/api/rabbitmq/queues?name=" + encode(queue), null);
		}
	}

	@Test
	void opensTheStreamAndReceivesSessionStarted() throws Exception {
		SseClient stream = open();

		assertThat(stream.sessionId).isNotBlank();
	}

	@Test
	void startsAConsumerReceivesAPublishedMessageAndConfirmsIt() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		JsonNode consumer = startConsumer(stream, "c1", queue, "MANUAL");
		assertThat(stream.next("consumer-started").at("/consumer/id").asText()).isEqualTo(consumer.get("id").asText());

		publish("", queue, "hola <b>ñ</b>");

		JsonNode received = stream.next("message-received");
		assertThat(received.get("consumerId").asText()).isEqualTo(consumer.get("id").asText());
		assertThat(received.at("/message/body").asText()).isEqualTo("hola <b>ñ</b>");
		assertThat(received.at("/message/exchange").asText()).isEmpty();
		assertThat(received.at("/message/routingKey").asText()).isEqualTo(queue);
		assertThat(received.at("/message/redelivered").asBoolean()).isFalse();
		assertThat(counters(queue)).containsExactly(0, 1);

		HttpResponse<String> resolved = resolve(stream, consumer, received.get("deliveryTag").asLong(), "ACK");

		assertThat(resolved.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(resolved.body()).get("outcome").asText()).isEqualTo("CONFIRMED");
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).containsExactly(0, 0));
	}

	@Test
	void twoConsumersOnTheSameQueueShareTheMessages() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		JsonNode first = startConsumer(stream, "c1", queue, "MANUAL");
		JsonNode second = startConsumer(stream, "c2", queue, "MANUAL");
		stream.next("consumer-started");
		stream.next("consumer-started");

		for (int i = 0; i < 4; i++) {
			publish("", queue, "m" + i);
		}

		JsonNode one = stream.next("message-received");
		JsonNode two = stream.next("message-received");
		assertThat(List.of(one.get("consumerId").asText(), two.get("consumerId").asText()))
			.containsExactlyInAnyOrder(first.get("id").asText(), second.get("id").asText());
		assertThat(stream.pending("message-received")).as("cada consumidor manual retiene un solo mensaje").isEmpty();
	}

	@Test
	void closingTheStreamReturnsThePendingMessageToTheQueue() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		startConsumer(stream, "c1", queue, "MANUAL");
		publish("", queue, "pendiente");
		stream.next("consumer-started");
		stream.next("message-received");
		assertThat(counters(queue)).containsExactly(0, 1);

		stream.close();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(counters(queue)).containsExactly(1, 0));
	}

	@Test
	void closingOneStreamLeavesTheConsumersOfAnotherOpen() throws Exception {
		SseClient first = open();
		SseClient second = open();
		String queue = queue(null);
		startConsumer(first, "c1", queue, "MANUAL");
		startConsumer(second, "c2", queue, "MANUAL");
		first.next("consumer-started");
		second.next("consumer-started");
		publish("", queue, "uno");
		publish("", queue, "dos");
		first.next("message-received");
		second.next("message-received");

		first.close();

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(counters(queue)).containsExactly(1, 1));
	}

	@Test
	void rejectingReportsTheDeadLetterQueueOrTheDiscard() throws Exception {
		SseClient stream = open();
		String dead = queue(null);
		String withDead = queue(dead);
		String withoutDead = queue(null);
		JsonNode a = startConsumer(stream, "a", withDead, "MANUAL");
		JsonNode b = startConsumer(stream, "b", withoutDead, "MANUAL");
		stream.next("consumer-started");
		stream.next("consumer-started");
		publish("", withDead, "x");
		publish("", withoutDead, "y");
		JsonNode first = stream.next("message-received");
		JsonNode second = stream.next("message-received");
		JsonNode toDead = first.get("consumerId").asText().equals(a.get("id").asText()) ? first : second;
		JsonNode toDiscard = toDead == first ? second : first;

		HttpResponse<String> dlq = resolve(stream, a, toDead.get("deliveryTag").asLong(), "REJECT");
		HttpResponse<String> discarded = resolve(stream, b, toDiscard.get("deliveryTag").asLong(), "REJECT");

		assertThat(JSON.readTree(dlq.body()).get("outcome").asText()).isEqualTo("DEAD_LETTERED");
		assertThat(JSON.readTree(discarded.body()).get("outcome").asText()).isEqualTo("DISCARDED");
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(dead)).containsExactly(1, 0));
	}

	@Test
	void requeueingReturnsTheMessageMarkedAsRedelivered() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		JsonNode consumer = startConsumer(stream, "c1", queue, "MANUAL");
		stream.next("consumer-started");
		publish("", queue, "otra vez");
		JsonNode first = stream.next("message-received");

		HttpResponse<String> response = resolve(stream, consumer, first.get("deliveryTag").asLong(), "REQUEUE");

		assertThat(JSON.readTree(response.body()).get("outcome").asText()).isEqualTo("REQUEUED");
		JsonNode again = stream.next("message-received");
		assertThat(again.at("/message/redelivered").asBoolean()).isTrue();
		assertThat(again.at("/message/body").asText()).isEqualTo("otra vez");
	}

	@Test
	void resolvingTwiceIsAConflictAndANameCannotBeRepeated() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		JsonNode consumer = startConsumer(stream, "c1", queue, "MANUAL");
		stream.next("consumer-started");
		publish("", queue, "x");
		long tag = stream.next("message-received").get("deliveryTag").asLong();
		resolve(stream, consumer, tag, "ACK");

		HttpResponse<String> again = resolve(stream, consumer, tag, "ACK");
		HttpResponse<String> repeatedName = post(
				"/api/rabbitmq/sessions/" + stream.sessionId + "/consumers",
				Map.of("name", "c1", "queue", queue, "ackMode", "MANUAL"));

		assertThat(again.statusCode()).isEqualTo(409);
		assertThat(JSON.readTree(again.body()).get("code").asText()).isEqualTo("CONFLICT");
		assertThat(repeatedName.statusCode()).isEqualTo(409);
	}

	@Test
	void stoppingAConsumerReturnsItsPendingMessageAndAnnouncesTheStop() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		JsonNode consumer = startConsumer(stream, "c1", queue, "MANUAL");
		stream.next("consumer-started");
		publish("", queue, "x");
		stream.next("message-received");

		HttpResponse<String> stopped = request("DELETE",
				"/api/rabbitmq/sessions/" + stream.sessionId + "/consumers/" + consumer.get("id").asText(), null);

		assertThat(stopped.statusCode()).isEqualTo(204);
		JsonNode event = stream.next("consumer-stopped");
		assertThat(event.get("reason").asText()).isEqualTo("USER");
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(counters(queue)).containsExactly(1, 0));
	}

	@Test
	void deletingTheQueueEndsTheConsumerWithAMessageInSpanish() throws Exception {
		SseClient stream = open();
		String queue = queue(null);
		startConsumer(stream, "c1", queue, "AUTOMATIC");
		stream.next("consumer-started");

		request("DELETE", "/api/rabbitmq/queues?name=" + encode(queue), null);

		JsonNode event = stream.next("consumer-stopped");
		assertThat(event.get("reason").asText()).isEqualTo("QUEUE_DELETED");
		assertThat(event.get("message").asText()).contains("se ha eliminado");
	}

	@Test
	void anUnknownSessionOrQueueIsNotFound() throws Exception {
		SseClient stream = open();

		HttpResponse<String> noSession = post("/api/rabbitmq/sessions/no-existe/consumers",
				Map.of("name", "c1", "queue", "x", "ackMode", "MANUAL"));
		HttpResponse<String> noQueue = post("/api/rabbitmq/sessions/" + stream.sessionId + "/consumers",
				Map.of("name", "c1", "queue", "no-existe-" + UUID.randomUUID(), "ackMode", "MANUAL"));

		assertThat(noSession.statusCode()).isEqualTo(404);
		assertThat(noQueue.statusCode()).isEqualTo(404);
		assertThat(JSON.readTree(noQueue.body()).get("code").asText()).isEqualTo("NOT_FOUND");
	}

	// ---- ayudas

	private SseClient open() throws Exception {
		SseClient stream = new SseClient("http://localhost:" + port + "/api/rabbitmq/session/events");
		streams.add(stream);
		stream.sessionId = stream.next("session-started").get("sessionId").asText();
		return stream;
	}

	private String queue(String deadLetterQueue) throws Exception {
		String name = "it-cons-http-" + UUID.randomUUID();
		Map<String, Object> body = deadLetterQueue == null ? Map.of("name", name)
				: Map.of("name", name, "deadLetterQueue", deadLetterQueue);
		assertThat(post("/api/rabbitmq/queues", body).statusCode()).isEqualTo(201);
		queues.add(name);
		return name;
	}

	private JsonNode startConsumer(SseClient stream, String name, String queue, String ackMode) throws Exception {
		HttpResponse<String> response = post("/api/rabbitmq/sessions/" + stream.sessionId + "/consumers",
				Map.of("name", name, "queue", queue, "ackMode", ackMode));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return JSON.readTree(response.body());
	}

	private void publish(String exchange, String routingKey, String body) throws Exception {
		HttpResponse<String> response = post("/api/rabbitmq/publications",
				Map.of("exchange", exchange, "routingKey", routingKey, "body", body));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).get("routed").asBoolean()).isTrue();
	}

	private HttpResponse<String> resolve(SseClient stream, JsonNode consumer, long tag, String action) throws Exception {
		return post("/api/rabbitmq/sessions/" + stream.sessionId + "/consumers/" + consumer.get("id").asText()
				+ "/messages/" + tag, Map.of("action", action));
	}

	/** Mensajes listos y pendientes de confirmar de la cola, leídos del listado de la propia aplicación. */
	private List<Integer> counters(String queue) throws Exception {
		JsonNode all = JSON.readTree(request("GET", "/api/rabbitmq/queues", null).body());
		for (JsonNode node : all) {
			if (node.get("name").asText().equals(queue)) {
				return List.of(node.get("ready").asInt(), node.get("unacknowledged").asInt());
			}
		}
		throw new AssertionError("La cola no está en el listado: " + queue);
	}

	private HttpResponse<String> post(String path, Object body) throws Exception {
		return request("POST", path, JSON.writeValueAsString(body));
	}

	private HttpResponse<String> request(String method, String path, String json) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Content-Type", "application/json");
		builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json));
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String encode(String name) {
		return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** Lector del flujo SSE: interpreta {@code event:} y {@code data:} y guarda los eventos recibidos. */
	private static final class SseClient {

		private final BlockingQueue<Map.Entry<String, JsonNode>> events = new LinkedBlockingQueue<>();

		private final Thread reader;

		private volatile Stream<String> lines;

		private String sessionId;

		private SseClient(String url) throws Exception {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream").build();
			HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
			this.lines = response.body();
			this.reader = new Thread(this::read, "sse-test-reader");
			this.reader.setDaemon(true);
			this.reader.start();
		}

		private void read() {
			String name = null;
			StringBuilder data = new StringBuilder();
			try {
				for (java.util.Iterator<String> it = lines.iterator(); it.hasNext();) {
					String line = it.next();
					if (line.startsWith("event:")) {
						name = line.substring("event:".length()).trim();
					}
					else if (line.startsWith("data:")) {
						data.append(line.substring("data:".length()));
					}
					else if (line.isEmpty() && name != null) {
						events.add(Map.entry(name, JSON.readTree(data.toString())));
						name = null;
						data.setLength(0);
					}
				}
			}
			catch (RuntimeException closed) {
				// el flujo se ha cerrado
			}
		}

		/** Espera el siguiente evento y comprueba que es del tipo indicado. */
		private JsonNode next(String expectedName) throws InterruptedException {
			Map.Entry<String, JsonNode> event = events.poll(10, TimeUnit.SECONDS);
			assertThat(event).as("evento «" + expectedName + "»").isNotNull();
			assertThat(event.getKey()).isEqualTo(expectedName);
			return event.getValue();
		}

		/** Eventos del tipo indicado que han llegado y aún no se han leído, tras darles un momento. */
		private List<String> pending(String name) throws InterruptedException {
			Thread.sleep(700);
			return events.stream().filter(event -> event.getKey().equals(name)).map(Map.Entry::getKey).toList();
		}

		private void close() {
			Stream<String> current = lines;
			if (current != null) {
				try {
					current.close();
				}
				catch (RuntimeException ignored) {
					// ya estaba cerrado
				}
			}
		}

	}

}
