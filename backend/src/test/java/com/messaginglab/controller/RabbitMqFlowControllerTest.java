package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowNode;
import com.messaginglab.flow.FlowService;
import com.messaginglab.flow.FlowSource;
import com.messaginglab.flow.FlowSourceStatus;
import com.messaginglab.flow.FlowTick;
import com.messaginglab.flow.FlowTopology;
import com.messaginglab.flow.FlowTopologyProvider;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RabbitMqFlowControllerTest {

	@WebMvcTest(RabbitMqFlowController.class)
	@Nested
	class Rest {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private FlowService flow;

		@Test
		void reportsTheStateOfTheSourceWithItsHint() throws Exception {
			given(flow.status()).willReturn(new FlowSourceStatus(FlowSourceStatus.State.DISABLED, "Activa el trazado"));

			mockMvc.perform(get("/api/rabbitmq/flow/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("DISABLED"))
				.andExpect(jsonPath("$.hint").value("Activa el trazado"));
		}

		@Test
		void returnsTheTopology() throws Exception {
			given(flow.topology()).willReturn(new FlowTopology(
					List.of(new FlowNode("router:x", FlowNode.Kind.ROUTER, "x", "topic"),
							new FlowNode("destination:q", FlowNode.Kind.DESTINATION, "q", null)),
					List.of(new FlowTopology.Edge("router:x", "destination:q", "a.*"))));

			mockMvc.perform(get("/api/rabbitmq/flow/topology"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes.length()").value(2))
				.andExpect(jsonPath("$.nodes[0].kind").value("ROUTER"))
				.andExpect(jsonPath("$.nodes[0].detail").value("topic"))
				.andExpect(jsonPath("$.edges[0].from").value("router:x"))
				.andExpect(jsonPath("$.edges[0].label").value("a.*"));
		}

		@Test
		void listsTheRecentEventsWith200AsTheDefaultLimit() throws Exception {
			given(flow.recentEvents(200)).willReturn(List.of(event(7, "router:x", List.of())));

			mockMvc.perform(get("/api/rabbitmq/flow/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].type").value("PUBLISH"))
				.andExpect(jsonPath("$[0].unrouted").value(true))
				.andExpect(jsonPath("$[0].body").value("hola"));
			verify(flow).recentEvents(200);
		}

		@Test
		void passesTheRequestedLimit() throws Exception {
			given(flow.recentEvents(5)).willReturn(List.of());

			mockMvc.perform(get("/api/rabbitmq/flow/events").param("limit", "5")).andExpect(status().isOk());

			verify(flow).recentEvents(5);
		}

		@Test
		void rejectsALimitOutOfRange() throws Exception {
			mockMvc.perform(get("/api/rabbitmq/flow/events").param("limit", "0")).andExpect(status().isBadRequest());
			mockMvc.perform(get("/api/rabbitmq/flow/events").param("limit", "1001")).andExpect(status().isBadRequest());

			Mockito.verifyNoInteractions(flow);
		}

	}

	static FlowEvent event(long id, String router, List<String> destinations) {
		return new FlowEvent(id, Instant.parse("2026-10-30T10:00:00Z"), FlowEvent.Type.PUBLISH, "producer:p", router,
				destinations, null, "k", 4, false, "hola", false, Map.of("a", 1));
	}

	@Nested
	class Stream {

		private final FakeSource source = new FakeSource();

		private final FlowService flow = new FlowService(source, new Topology(), Clock.systemUTC());

		private final RabbitMqFlowController controller = new RabbitMqFlowController(flow);

		private final FakeEmitter emitter = new FakeEmitter();

		@Test
		void sendsTheStateAndTheTopologyWhenConnecting() {
			controller.stream(emitter);

			await().untilAsserted(() -> assertThat(emitter.names()).containsExactly("status", "topology"));
		}

		@Test
		void sendsTheEventsAndTicksAsJson() {
			controller.stream(emitter);
			await().until(() -> source.sink != null);

			source.sink.accept(event(0, "router:x", List.of("destination:q")));
			flow.tick();

			await().untilAsserted(() -> assertThat(emitter.names()).containsSubsequence("status", "flow-event", "tick"));
			assertThat(emitter.dataOf("flow-event")).isInstanceOf(com.messaginglab.api.model.FlowEvent.class);
			assertThat(emitter.dataOf("tick")).isInstanceOf(com.messaginglab.api.model.FlowTick.class);
		}

		@Test
		void closingTheConnectionReleasesTheSubscriptionAndStopsTheSource() {
			controller.stream(emitter);
			await().until(() -> source.started);

			emitter.completion.run();

			await().until(() -> !source.started);
		}

		@Test
		void aFailedSendEndsTheStream() {
			controller.stream(emitter);
			await().until(() -> source.sink != null);
			emitter.failSends = true;

			source.sink.accept(event(0, "router:x", List.of()));

			await().until(() -> emitter.completedWithError != null);
			await().until(() -> !source.started);
		}

		class FakeSource implements FlowSource {

			volatile boolean started;

			volatile Consumer<FlowEvent> sink;

			@Override
			public FlowSourceStatus status() {
				return new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null);
			}

			@Override
			public void start(Consumer<FlowEvent> sink) {
				this.sink = sink;
				started = true;
			}

			@Override
			public void stop() {
				started = false;
			}

		}

		static class Topology implements FlowTopologyProvider {

			@Override
			public FlowTopology topology() {
				return FlowTopology.EMPTY;
			}

			@Override
			public Map<String, Long> ready() {
				return Map.of();
			}

		}

	}

	record Sent(String name, Object data) {
	}

	static final class FakeEmitter extends SseEmitter {

		private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

		volatile Runnable completion = () -> {
		};

		volatile boolean failSends;

		volatile Throwable completedWithError;

		FakeEmitter() {
			super(0L);
		}

		List<String> names() {
			synchronized (sent) {
				return sent.stream().map(Sent::name).toList();
			}
		}

		Object dataOf(String name) {
			synchronized (sent) {
				return sent.stream().filter(item -> name.equals(item.name())).findFirst().orElseThrow().data();
			}
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
		public void completeWithError(Throwable ex) {
			this.completedWithError = ex;
		}

	}

}
