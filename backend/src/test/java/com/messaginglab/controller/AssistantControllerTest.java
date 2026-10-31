package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.assistant.AssistantService;
import com.messaginglab.assistant.AssistantUnavailableException;
import com.messaginglab.assistant.Cancellation;
import com.messaginglab.assistant.ContextBuilder;
import com.messaginglab.assistant.KnowledgeBase;
import com.messaginglab.assistant.LlmClient;
import com.messaginglab.assistant.LlmStatus;
import com.messaginglab.assistant.Message;
import com.messaginglab.assistant.QuestionClassifier;
import com.messaginglab.assistant.Snapshot;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.ValidationException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AssistantControllerTest {

	@WebMvcTest(AssistantController.class)
	@Nested
	class Rest {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private AssistantService assistant;

		@Test
		void reportsTheStateOfTheModelWithItsMessage() throws Exception {
			given(assistant.status()).willReturn(new LlmStatus(LlmStatus.State.PREPARING, "Se está descargando el modelo."));

			mockMvc.perform(get("/api/assistant/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("PREPARING"))
				.andExpect(jsonPath("$.message").value("Se está descargando el modelo."));
		}

		@Test
		void aReadyModelHasNoMessage() throws Exception {
			given(assistant.status()).willReturn(LlmStatus.ready());

			mockMvc.perform(get("/api/assistant/status")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READY"));
		}

		private org.springframework.test.web.servlet.ResultActions chat(String body) throws Exception {
			return mockMvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(body));
		}

		@Test
		void anInvalidQuestionAnswers400BeforeStreaming() throws Exception {
			given(assistant.begin(any(), any())).willThrow(new ValidationException("Escribe una pregunta."));

			chat("{\"question\":\"  \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.message").value("Escribe una pregunta."));
		}

		@Test
		void aMissingQuestionIsRejectedByTheContract() throws Exception {
			chat("{}").andExpect(status().isBadRequest());
			chat("{\"question\":\"" + "a".repeat(501) + "\"}").andExpect(status().isBadRequest());
		}

		@Test
		void aBusyAssistantAnswers409() throws Exception {
			given(assistant.begin(any(), any())).willThrow(new ConflictException("El asistente está ocupado con otra pregunta. Espera a que termine."));

			chat("{\"question\":\"hola\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
		}

		@Test
		void aModelThatIsNotReadyAnswers503WithItsOwnCode() throws Exception {
			given(assistant.begin(any(), any())).willThrow(new AssistantUnavailableException("El modelo «qwen2.5:3b» todavía no está descargado."));

			chat("{\"question\":\"hola\"}").andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value("El modelo «qwen2.5:3b» todavía no está descargado."));
		}

	}

	@Nested
	class Stream {

		private final FakeLlm llm = new FakeLlm();

		private final AssistantService service = new AssistantService(() -> new Snapshot(java.time.Instant.EPOCH,
				new Snapshot.Broker(true, "4.4.0", null), new Snapshot.Trace(Snapshot.Trace.State.ACTIVE, null), List.of(), List.of(),
				List.of(), List.of(), new Snapshot.Load(0, 100, 0)), llm, new QuestionClassifier(), new ContextBuilder(new KnowledgeBase()),
				Clock.systemUTC());

		private final AssistantController controller = new AssistantController(service);

		private final FakeEmitter emitter = new FakeEmitter();

		private SseEmitter open() {
			return controller.stream(service.begin("¿Cómo va la cola cocina?", List.of()), emitter);
		}

		@Test
		void sendsEachTokenThenDoneAndCompletes() {
			llm.tokens = List.of("Todo ", "bien.");

			open();

			await().until(() -> emitter.completed);
			assertThat(emitter.names()).containsExactly("token", "token", "done");
			assertThat(emitter.dataOf(0)).isInstanceOf(com.messaginglab.api.model.AssistantToken.class);
			assertThat(((com.messaginglab.api.model.AssistantToken) emitter.dataOf(0)).getText()).isEqualTo("Todo ");
		}

		@Test
		void ifTheModelFailsHalfwayTheErrorIsSentAfterWhatWasAlreadyDelivered() {
			llm.tokens = List.of("Todo ");
			llm.failure = new AssistantUnavailableException("El modelo ha dejado de responder a mitad de la respuesta.");

			open();

			await().until(() -> emitter.completed);
			assertThat(emitter.names()).containsExactly("token", "error");
			assertThat(((com.messaginglab.api.model.AssistantError) emitter.dataOf(1)).getMessage())
				.isEqualTo("El modelo ha dejado de responder a mitad de la respuesta.");
		}

		@Test
		void anUnexpectedFailureGetsAGenericMessageWithoutDetails() {
			llm.tokens = List.of();
			llm.failure = new IllegalStateException("detalle técnico secreto");

			open();

			await().until(() -> emitter.completed);
			assertThat(emitter.names()).containsExactly("error");
			assertThat(((com.messaginglab.api.model.AssistantError) emitter.dataOf(0)).getMessage())
				.isEqualTo("El asistente ha fallado. Inténtalo de nuevo en un momento.");
		}

		@Test
		void whenTheClientLeavesTheGenerationIsCancelled() throws Exception {
			llm.waitForCancel = true;
			open();
			await().until(() -> llm.started.get());

			emitter.completion.run();

			await().until(() -> llm.cancelled.get());
			await().until(() -> emitter.completed);
			assertThat(emitter.names()).doesNotContain("done");
		}

		@Test
		void whenSendingFailsTheGenerationIsCancelledAndTheAssistantFreed() {
			emitter.failSends = true;
			llm.tokens = List.of("a", "b", "c");

			open();

			await().until(() -> emitter.completed);
			assertThat(llm.delivered).isLessThanOrEqualTo(1);
			assertThat(service.begin("¿Otra?", List.of())).isNotNull();
		}

		@Test
		void theAssistantIsFreeAgainWhenTheStreamEnds() {
			open();
			await().until(() -> emitter.completed);

			service.begin("¿Y otra?", List.of()).close();
		}

		@Test
		void aSecondQuestionWhileTheFirstIsBeingAnsweredIsRefused() {
			llm.waitForCancel = true;
			open();
			await().until(() -> llm.started.get());

			org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.begin("¿Y otra?", List.of())).isInstanceOf(ConflictException.class);

			emitter.completion.run();
			await().until(() -> emitter.completed);
		}

		private static final class FakeLlm implements LlmClient {

			List<String> tokens = List.of("ok");

			RuntimeException failure;

			boolean waitForCancel;

			final AtomicBoolean started = new AtomicBoolean();

			final AtomicBoolean cancelled = new AtomicBoolean();

			int delivered;

			@Override
			public LlmStatus status() {
				return LlmStatus.ready();
			}

			@Override
			public void chat(List<Message> messages, Consumer<String> onToken, Cancellation cancel) {
				started.set(true);
				if (waitForCancel) {
					cancel.onCancel(() -> cancelled.set(true));
					await().until(cancel::isCancelled);
					return;
				}
				for (String token : tokens) {
					if (cancel.isCancelled()) {
						return;
					}
					onToken.accept(token);
					delivered++;
				}
				if (failure != null) {
					throw failure;
				}
			}

		}

		private static final class FakeEmitter extends SseEmitter {

			record Sent(String name, Object data) {
			}

			private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

			volatile Runnable completion = () -> { };

			volatile boolean completed;

			volatile boolean failSends;

			FakeEmitter() {
				super(0L);
			}

			List<String> names() {
				synchronized (sent) {
					return sent.stream().map(Sent::name).toList();
				}
			}

			Object dataOf(int index) {
				return sent.get(index).data();
			}

			@Override
			public void send(SseEventBuilder builder) throws IOException {
				if (failSends) {
					throw new IOException("conexión cerrada");
				}
				String name = null;
				Object data = null;
				for (DataWithMediaType part : builder.build()) {
					if (part.getData() instanceof String text) {
						if (text.startsWith("event:")) {
							name = text.substring("event:".length()).lines().findFirst().orElse("").trim();
						}
					}
					else {
						data = part.getData();
					}
				}
				sent.add(new Sent(name, data));
			}

			@Override
			public void onCompletion(Runnable callback) {
				this.completion = callback;
			}

			@Override
			public void complete() {
				completed = true;
			}

		}

	}

}
