package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.messaginglab.api.model.ConsumerStartedEvent;
import com.messaginglab.api.model.ConsumerStoppedEvent;
import com.messaginglab.api.model.MessageReceivedEvent;
import com.messaginglab.api.model.SessionStartedEvent;
import com.messaginglab.api.model.StopReason;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.MessageAction;
import com.messaginglab.service.ConsumerSessionService;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.InMemoryBrokerTopology;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** El flujo de eventos con un emisor falso: sin servidor, sin red y sin RabbitMQ. */
class RabbitMqSessionEventsControllerTest {

	private final InMemoryBrokerTopology topology = new InMemoryBrokerTopology();

	private final FakeBrokerConsumers broker = new FakeBrokerConsumers();

	private final ConsumerSessionService sessions = new ConsumerSessionService(topology, broker);

	private RabbitMqSessionEventsController controller;

	@BeforeEach
	void setUp() {
		topology.createQueue("cola", null);
		controller = new RabbitMqSessionEventsController(sessions, Duration.ofMinutes(10));
	}

	@AfterEach
	void tearDown() {
		controller.shutdown();
	}

	@Test
	void theFirstEventIsSessionStartedWithAUsableSessionId() {
		FakeEmitter emitter = new FakeEmitter();

		controller.open(emitter);

		assertThat(emitter.sent).hasSize(1);
		assertThat(emitter.sent.get(0).name()).isEqualTo("session-started");
		SessionStartedEvent event = (SessionStartedEvent) emitter.sent.get(0).data();
		assertThat(event.getSessionId()).isNotBlank();
		assertThat(sessions.startConsumer(event.getSessionId(), "c1", "cola", AckMode.AUTOMATIC)).isNotNull();
	}

	@Test
	void eachEventHasItsNameJsonMediaTypeAndContent() {
		FakeEmitter emitter = new FakeEmitter();
		controller.open(emitter);
		String session = sessionId(emitter);

		ConsumerInfo consumer = sessions.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("<b>hola</b>");
		sessions.resolve(session, consumer.id(), tag, MessageAction.ACK);
		sessions.stopConsumer(session, consumer.id());

		assertThat(emitter.sent).extracting(Sent::name)
			.containsExactly("session-started", "consumer-started", "message-received", "consumer-stopped");
		assertThat(emitter.sent).allMatch(sent -> MediaType.APPLICATION_JSON.equals(sent.mediaType()));

		ConsumerStartedEvent started = (ConsumerStartedEvent) emitter.sent.get(1).data();
		assertThat(started.getConsumer().getId()).isEqualTo(consumer.id());
		assertThat(started.getConsumer().getName()).isEqualTo("c1");
		assertThat(started.getConsumer().getQueue()).isEqualTo("cola");
		assertThat(started.getConsumer().getAckMode().name()).isEqualTo("MANUAL");

		MessageReceivedEvent received = (MessageReceivedEvent) emitter.sent.get(2).data();
		assertThat(received.getConsumerId()).isEqualTo(consumer.id());
		assertThat(received.getDeliveryTag()).isEqualTo(tag);
		assertThat(received.getMessage().getBody()).isEqualTo("<b>hola</b>");
		assertThat(received.getMessage().getExchange()).isEqualTo("ex");
		assertThat(received.getMessage().getRoutingKey()).isEqualTo("rk");
		assertThat(received.getMessage().getRedelivered()).isFalse();

		ConsumerStoppedEvent stopped = (ConsumerStoppedEvent) emitter.sent.get(3).data();
		assertThat(stopped.getConsumerId()).isEqualTo(consumer.id());
		assertThat(stopped.getReason()).isEqualTo(StopReason.USER);
		assertThat(stopped.getMessage()).isEqualTo("El consumidor «c1» se ha detenido.");
	}

	@Test
	void whenTheQueueIsDeletedTheStopEventCarriesTheReasonAndAMessageInSpanish() {
		FakeEmitter emitter = new FakeEmitter();
		controller.open(emitter);
		sessions.startConsumer(sessionId(emitter), "c1", "cola", AckMode.MANUAL);

		broker.last().brokerStops(com.messaginglab.model.StopReason.QUEUE_DELETED);

		ConsumerStoppedEvent stopped = (ConsumerStoppedEvent) emitter.sent.get(2).data();
		assertThat(stopped.getReason()).isEqualTo(StopReason.QUEUE_DELETED);
		assertThat(stopped.getMessage()).contains("se ha eliminado");
	}

	@Test
	void sendsACommentHeartbeatPeriodicallyThatIsNotAnEvent() {
		RabbitMqSessionEventsController quick = new RabbitMqSessionEventsController(sessions, Duration.ofMillis(30));
		try {
			FakeEmitter emitter = new FakeEmitter();
			quick.open(emitter);

			await().atMost(Duration.ofSeconds(5)).until(() -> emitter.heartbeats() >= 3);

			assertThat(emitter.sent).filteredOn(Sent::isComment).allMatch(sent -> sent.name() == null);
			assertThat(emitter.sent.get(0).name()).isEqualTo("session-started");
		}
		finally {
			quick.shutdown();
		}
	}

	@Test
	void closingTheEmitterStopsTheConsumersOfThatSessionOnly() {
		FakeEmitter first = new FakeEmitter();
		FakeEmitter second = new FakeEmitter();
		controller.open(first);
		controller.open(second);
		sessions.startConsumer(sessionId(first), "a", "cola", AckMode.MANUAL);
		sessions.startConsumer(sessionId(first), "b", "cola", AckMode.AUTOMATIC);
		sessions.startConsumer(sessionId(second), "c", "cola", AckMode.MANUAL);

		first.completion.run();

		assertThat(broker.started().get(0).stopped()).isTrue();
		assertThat(broker.started().get(1).stopped()).isTrue();
		assertThat(broker.started().get(2).stopped()).isFalse();
	}

	@Test
	void aTimeoutOrAnErrorOfTheEmitterAlsoClosesTheSession() {
		for (Consumer<FakeEmitter> end : List.<Consumer<FakeEmitter>>of(emitter -> emitter.timeout.run(),
				emitter -> emitter.error.accept(new IOException("roto")))) {
			FakeEmitter emitter = new FakeEmitter();
			controller.open(emitter);
			sessions.startConsumer(sessionId(emitter), "c", "cola", AckMode.MANUAL);
			int index = broker.started().size() - 1;

			end.accept(emitter);

			assertThat(broker.started().get(index).stopped()).isTrue();
		}
	}

	@Test
	void closingTwiceIsHarmless() {
		FakeEmitter emitter = new FakeEmitter();
		controller.open(emitter);
		sessions.startConsumer(sessionId(emitter), "c", "cola", AckMode.MANUAL);

		emitter.completion.run();
		emitter.completion.run();
		emitter.timeout.run();

		assertThat(broker.started()).hasSize(1);
	}

	@Test
	void ifSendingFailsTheSessionIsClosedAndTheConsumersStopped() {
		FakeEmitter emitter = new FakeEmitter();
		controller.open(emitter);
		sessions.startConsumer(sessionId(emitter), "c", "cola", AckMode.MANUAL);
		emitter.failSends = true;

		broker.last().deliver("nadie lo recibe");

		assertThat(emitter.completedWithError).isNotNull();
		assertThat(broker.last().stopped()).isTrue();
	}

	@Test
	void afterTheSessionIsClosedNothingMoreIsSent() {
		FakeEmitter emitter = new FakeEmitter();
		controller.open(emitter);
		sessions.startConsumer(sessionId(emitter), "c", "cola", AckMode.MANUAL);
		emitter.completion.run();
		int before = emitter.sent.size();

		broker.last().deliver("tarde");

		assertThat(emitter.sent).hasSize(before);
	}

	private static String sessionId(FakeEmitter emitter) {
		return ((SessionStartedEvent) emitter.sent.get(0).data()).getSessionId();
	}

	private record Sent(String name, Object data, MediaType mediaType, boolean isComment) {
	}

	/** Emisor que guarda lo que se le envía y expone las funciones de cierre que se le registran. */
	private static final class FakeEmitter extends SseEmitter {

		private final List<Sent> sent = new ArrayList<>();

		private Runnable completion = () -> {
		};

		private Runnable timeout = () -> {
		};

		private Consumer<Throwable> error = error -> {
		};

		private boolean failSends;

		private Throwable completedWithError;

		private FakeEmitter() {
			super(0L);
		}

		private synchronized int heartbeats() {
			return (int) sent.stream().filter(Sent::isComment).count();
		}

		@Override
		public synchronized void send(SseEventBuilder builder) throws IOException {
			if (failSends) {
				throw new IOException("conexión cerrada");
			}
			String name = null;
			Object data = null;
			MediaType mediaType = null;
			boolean comment = false;
			for (DataWithMediaType part : builder.build()) {
				if (part.getData() instanceof String text) {
					if (text.startsWith("event:")) {
						name = text.substring("event:".length()).lines().findFirst().orElse("").trim();
					}
					else if (text.startsWith(":")) {
						comment = true;
					}
				}
				else {
					data = part.getData();
					mediaType = part.getMediaType();
				}
			}
			sent.add(new Sent(name, data, mediaType, comment));
		}

		@Override
		public void onCompletion(Runnable callback) {
			this.completion = callback;
		}

		@Override
		public void onTimeout(Runnable callback) {
			this.timeout = callback;
		}

		@Override
		public void onError(Consumer<Throwable> callback) {
			this.error = callback;
		}

		@Override
		public void completeWithError(Throwable ex) {
			this.completedWithError = ex;
		}

	}

}
