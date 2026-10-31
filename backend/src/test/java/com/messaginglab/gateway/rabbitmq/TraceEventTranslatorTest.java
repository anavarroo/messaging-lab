package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.flow.FlowEvent;
import com.messaginglab.support.MutableClock;
import com.rabbitmq.client.impl.LongStringHelper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Los mensajes de ejemplo reproducen los que RabbitMQ 4.4 entrega realmente al exchange del trazado. */
class TraceEventTranslatorTest {

	private static final String OWN_QUEUE = "ml.flow.trace.abc";

	private static final String CONNECTION = "172.17.0.1:5000 -> 172.17.0.2:5672";

	private final TraceEventTranslator translator = new TraceEventTranslator(
			new MutableClock(Instant.parse("2026-10-30T10:00:00Z")), OWN_QUEUE);

	private static Object text(String value) {
		return LongStringHelper.asLongString(value);
	}

	private static Map<String, Object> headers(String exchange, String key) {
		Map<String, Object> headers = new HashMap<>();
		headers.put("exchange_name", text(exchange));
		headers.put("routing_keys", List.of(text(key)));
		headers.put("connection", text(CONNECTION));
		headers.put("channel", 1);
		headers.put("user", text("guest"));
		headers.put("properties", new HashMap<>(Map.of("content_type", text("application/json"))));
		return headers;
	}

	private FlowEvent translate(String key, Map<String, Object> headers, String body) {
		return translator.translate(key, headers, body.getBytes(StandardCharsets.UTF_8)).orElseThrow();
	}

	@Test
	void translatesARoutedPublication() {
		Map<String, Object> headers = headers("ex1", "a.b");
		headers.put("routed_queues", List.of(text("q1"), text("q2")));
		((Map<String, Object>) headers.get("properties")).put("headers", new HashMap<>(Map.of("h", text("v"))));

		FlowEvent event = translate("publish.ex1", headers, "{\"t\":1}");

		assertThat(event.type()).isEqualTo(FlowEvent.Type.PUBLISH);
		assertThat(event.producer()).isEqualTo("producer:" + CONNECTION);
		assertThat(event.router()).isEqualTo("router:ex1");
		assertThat(event.destinations()).containsExactly("destination:q1", "destination:q2");
		assertThat(event.key()).isEqualTo("a.b");
		assertThat(event.sizeBytes()).isEqualTo(7);
		assertThat(event.body()).isEqualTo("{\"t\":1}");
		assertThat(event.unrouted()).isFalse();
		assertThat(event.time()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
		assertThat(event.properties()).containsEntry("content_type", "application/json")
			.containsEntry("headers", Map.of("h", "v"));
	}

	@Test
	void aPublisherThatNamesItselfWithAppIdIsRecognisedByThatName() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());
		((Map<String, Object>) headers.get("properties")).put("app_id", text("Brazo robótico"));

		FlowEvent event = translate("publish.ex1", headers, "x");

		assertThat(event.producer()).isEqualTo("producer:app:Brazo robótico");
	}

	@Test
	void aBlankAppIdFallsBackToTheConnection() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());
		((Map<String, Object>) headers.get("properties")).put("app_id", text(" "));

		assertThat(translate("publish.ex1", headers, "x").producer()).isEqualTo("producer:" + CONNECTION);
	}

	@Test
	void anUnroutedPublicationHasNoDestinations() {
		Map<String, Object> headers = headers("ex1", "zzz");
		headers.put("routed_queues", List.of());

		FlowEvent event = translate("publish.ex1", headers, "x");

		assertThat(event.destinations()).isEmpty();
		assertThat(event.unrouted()).isTrue();
	}

	@Test
	void theDefaultExchangeHasAnEmptyName() {
		Map<String, Object> headers = headers("", "cola");
		headers.put("routed_queues", List.of(text("cola")));

		assertThat(translate("publish.", headers, "x").router()).isEqualTo("router:");
	}

	@Test
	void translatesADeliveryWithItsConsumerAndTheRedeliveredFlag() {
		Map<String, Object> headers = headers("ex1", "a.b");
		headers.put("redelivered", 1);
		headers.put("channel", 3);

		FlowEvent event = translate("deliver.q1", headers, "hola");

		assertThat(event.type()).isEqualTo(FlowEvent.Type.DELIVER);
		assertThat(event.destinations()).containsExactly("destination:q1");
		assertThat(event.router()).isEqualTo("router:ex1");
		assertThat(event.consumer()).isEqualTo("consumer:" + CONNECTION + "|3|q1");
		assertThat(event.redelivered()).isTrue();
		assertThat(event.producer()).isNull();
	}

	@Test
	void aFirstDeliveryIsNotRedelivered() {
		Map<String, Object> headers = headers("ex1", "a.b");
		headers.put("redelivered", 0);

		assertThat(translate("deliver.q1", headers, "x").redelivered()).isFalse();
	}

	@Test
	void ignoresTheDeliveriesOfItsOwnQueue() {
		assertThat(translator.translate("deliver." + OWN_QUEUE, headers("ex1", "a"), new byte[0])).isEmpty();
	}

	@Test
	void ignoresOtherRoutingKeysAndMessagesWithoutHeaders() {
		assertThat(translator.translate("queue.created", headers("ex1", "a"), new byte[0])).isEmpty();
		assertThat(translator.translate("publish.ex1", null, new byte[0])).isEmpty();
	}

	@Test
	void truncatesTheBodyTo4KbButKeepsTheRealSize() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());

		FlowEvent event = translate("publish.ex1", headers, "x".repeat(10_000));

		assertThat(event.body()).hasSize(4_096);
		assertThat(event.bodyTruncated()).isTrue();
		assertThat(event.sizeBytes()).isEqualTo(10_000);
	}

	@Test
	void doesNotMarkASmallBodyAsTruncated() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());

		assertThat(translate("publish.ex1", headers, "x".repeat(4_096)).bodyTruncated()).isFalse();
	}

	@Test
	void keepsHtmlAsPlainText() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());

		assertThat(translate("publish.ex1", headers, "<script>alert(1)</script>").body())
			.isEqualTo("<script>alert(1)</script>");
	}

	@Test
	void turnsBinaryHeaderValuesIntoText() {
		Map<String, Object> headers = headers("ex1", "a");
		headers.put("routed_queues", List.of());
		((Map<String, Object>) headers.get("properties")).put("headers", new HashMap<>(Map.of("raw", new byte[] { 1, 2, 3 })));

		FlowEvent event = translate("publish.ex1", headers, "x");

		assertThat(event.properties().get("headers")).isEqualTo(Map.of("raw", "[3 bytes]"));
	}

}
