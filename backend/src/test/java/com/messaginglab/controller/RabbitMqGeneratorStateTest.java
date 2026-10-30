package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.FakeMessagePublisher;
import com.messaginglab.support.ManualScheduler;
import com.messaginglab.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * El generador con sus servicios de verdad (solo el publicador y el tiempo son falsos): lo que devuelve la API al
 * volver a consultar es el estado real, no el de la última respuesta.
 */
@WebMvcTest(RabbitMqGeneratorController.class)
@Import(RabbitMqGeneratorStateTest.Config.class)
class RabbitMqGeneratorStateTest {

	private static final String BASE = "/api/rabbitmq/generator";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TestConfiguration
	static class Config {

		@Bean
		MutableClock clock() {
			return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
		}

		@Bean
		ManualScheduler scheduler(MutableClock clock) {
			return new ManualScheduler(clock);
		}

		@Bean
		FakeMessagePublisher publisher() {
			return new FakeMessagePublisher();
		}

		@Bean
		FakeBrokerConsumers brokerConsumers() {
			return new FakeBrokerConsumers();
		}

		@Bean
		EmitterService emitters(FakeMessagePublisher publisher, ManualScheduler scheduler, MutableClock clock) {
			return new EmitterService(new com.messaginglab.generator.MessagePublisher() {
				@Override
				public void verify(com.messaginglab.generator.Destination destination) {
				}

				@Override
				public boolean publish(com.messaginglab.generator.Destination destination, String body) {
					return publisher.publish(new FakeMessagePublisher.TestDestination("x"), body);
				}
			}, scheduler, clock, () -> new Random(1), new GeneratorLimits());
		}

		@Bean
		SimulatedConsumerService simulated(FakeBrokerConsumers consumers, ManualScheduler scheduler, MutableClock clock) {
			return new SimulatedConsumerService(consumers, scheduler, clock, () -> new Random(1));
		}

	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ManualScheduler scheduler;

	@Autowired
	private FakeMessagePublisher publisher;

	@Autowired
	private FakeBrokerConsumers brokerConsumers;

	@Autowired
	private EmitterService emitters;

	@Autowired
	private SimulatedConsumerService simulated;

	@BeforeEach
	void clean() {
		emitters.list().forEach(emitter -> emitters.delete(emitter.id()));
		simulated.list().forEach(consumer -> simulated.delete(consumer.id()));
		emitters.limits();
	}

	private String createEmitter(String name, int fixedMs) throws Exception {
		MvcResult result = mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"%s","destination":{"type":"QUEUE","queue":"cocina"},"interval":{"mode":"FIXED","fixedMs":%d},
				 "template":"{\\"n\\": {{contador}}}"}""".formatted(name, fixedMs)))
			.andExpect(status().isCreated())
			.andReturn();
		return JSON.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private void act(String path, String id, String action) throws Exception {
		mockMvc.perform(post(BASE + "/" + path + "/" + id + "/actions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"" + action + "\"}")).andExpect(status().isOk());
	}

	private void pass(long ms) {
		scheduler.advance(Duration.ofMillis(ms));
	}

	@Test
	void anEmitterThatRunsShowsItsRealCountersEachTimeItIsQueried() throws Exception {
		String id = createEmitter("robot", 100);
		act("emitters", id, "START");

		pass(1000);
		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(jsonPath("$[0].status").value("RUNNING"))
			.andExpect(jsonPath("$[0].published").value(11));
		pass(1000);
		mockMvc.perform(get(BASE + "/emitters")).andExpect(jsonPath("$[0].published").value(21));
	}

	@Test
	void theRealRateAppearsInTheList() throws Exception {
		String id = createEmitter("ritmo", 100);
		act("emitters", id, "START");

		pass(4000);

		MvcResult result = mockMvc.perform(get(BASE + "/emitters")).andReturn();
		double rate = JSON.readTree(result.getResponse().getContentAsString()).get(0).get("ratePerSecond").asDouble();
		assertThat(rate).isBetween(9.0, 11.0);
	}

	@Test
	void pausingStopsTheCountersAndStoppingKeepsThemUntilItStartsAgain() throws Exception {
		String id = createEmitter("pausa", 100);
		act("emitters", id, "START");
		pass(500);
		act("emitters", id, "PAUSE");
		pass(5000);

		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(jsonPath("$[0].status").value("PAUSED"))
			.andExpect(jsonPath("$[0].published").value(6));
		act("emitters", id, "RESUME");
		pass(200);
		mockMvc.perform(get(BASE + "/emitters")).andExpect(jsonPath("$[0].published").value(8));
		act("emitters", id, "STOP");
		pass(5000);
		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(jsonPath("$[0].status").value("STOPPED"))
			.andExpect(jsonPath("$[0].published").value(8));
	}

	@Test
	void anEmitterThatReachedItsLimitIsReportedAsFinishedWithItsMessage() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"tres","destination":{"type":"QUEUE","queue":"cocina"},"interval":{"mode":"FIXED","fixedMs":10},
				 "template":"x","maxMessages":3}"""))
			.andExpect(status().isCreated());
		String id = JSON.readTree(mockMvc.perform(get(BASE + "/emitters")).andReturn().getResponse().getContentAsString()).get(0).get("id").asString();
		act("emitters", id, "START");

		pass(10_000);

		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(jsonPath("$[0].status").value("FINISHED"))
			.andExpect(jsonPath("$[0].published").value(3))
			.andExpect(jsonPath("$[0].stopMessage").value("Ha publicado los 3 mensajes que se le pidieron."));
	}

	@Test
	void theLimitIsEnforcedAndTheSettingsShowTheRealUse() throws Exception {
		String first = createEmitter("a", 10);
		String second = createEmitter("b", 10);
		act("emitters", first, "START");

		mockMvc.perform(post(BASE + "/emitters/" + second + "/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("el límite es 100")));
		mockMvc.perform(get(BASE + "/settings"))
			.andExpect(jsonPath("$.ratePerSecondInUse").value(100.0))
			.andExpect(jsonPath("$.maxRatePerSecond").value(100));

		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":50}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No se puede bajar el límite a 50")));
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":200}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maxRatePerSecond").value(200));
		act("emitters", second, "START");
		mockMvc.perform(get(BASE + "/settings")).andExpect(jsonPath("$.ratePerSecondInUse").value(200.0));
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":100}")).andExpect(status().isConflict());
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":501}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":500}")).andExpect(status().isOk());
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":100}"))
			.andExpect(status().isConflict());
		emitters.stopAll();
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":100}")).andExpect(status().isOk());
	}

	@Test
	void stopAllStopsEmittersAndSimulatedConsumersAndTheListsShowIt() throws Exception {
		String emitter = createEmitter("a", 100);
		act("emitters", emitter, "START");
		String consumer = createConsumer("proc");
		act("consumers", consumer, "START");

		mockMvc.perform(post(BASE + "/stop-all")).andExpect(status().isOk());
		int sent = publisher.sent().size();
		pass(5000);

		assertThat(publisher.sent()).hasSize(sent);
		mockMvc.perform(get(BASE + "/emitters")).andExpect(jsonPath("$[0].status").value("STOPPED"));
		mockMvc.perform(get(BASE + "/consumers")).andExpect(jsonPath("$[0].status").value("STOPPED"));
		assertThat(brokerConsumers.last().stopped()).isTrue();
	}

	@Test
	void theCreatedAreStillThereWhenTheListIsQueriedAgainAndDeletedOnesAreGone() throws Exception {
		String keep = createEmitter("queda", 100);
		String gone = createEmitter("se va", 100);

		mockMvc.perform(delete(BASE + "/emitters/" + gone)).andExpect(status().isNoContent());

		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(keep));
		mockMvc.perform(post(BASE + "/emitters/" + gone + "/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void anEmitterCanOnlyBeEditedWhileStoppedAndItsNameStaysUnique() throws Exception {
		String id = createEmitter("editable", 100);
		createEmitter("otro", 100);
		act("emitters", id, "START");
		String body = """
				{"name":"editable","destination":{"type":"QUEUE","queue":"cocina"},"interval":{"mode":"FIXED","fixedMs":300},"template":"x"}""";

		mockMvc.perform(put(BASE + "/emitters/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Solo se puede editar un emisor parado")));
		act("emitters", id, "STOP");
		mockMvc.perform(put(BASE + "/emitters/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.interval.fixedMs").value(300));
		mockMvc.perform(put(BASE + "/emitters/" + id).contentType(MediaType.APPLICATION_JSON).content(body.replace("editable", "otro")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Ya hay un emisor con el nombre «otro»."));
	}

	@Test
	void anInvalidTemplateIsRejectedWhenCreatingAndNothingIsCreated() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"mala","destination":{"type":"QUEUE","queue":"cocina"},"interval":{"mode":"FIXED","fixedMs":100},"template":"{{numero(10,1)}}"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("el mínimo (10) no puede ser mayor que el máximo (1)")));

		mockMvc.perform(get(BASE + "/emitters")).andExpect(jsonPath("$.length()").value(0));
	}

	// ---- consumidores simulados

	private String createConsumer(String name) throws Exception {
		MvcResult result = mockMvc.perform(post(BASE + "/consumers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"%s","queue":"cocina","processing":{"mode":"FIXED","fixedMs":100},"failurePercent":0,"onFailure":"REJECT"}""".formatted(name)))
			.andExpect(status().isCreated())
			.andReturn();
		return JSON.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	@Test
	void aSimulatedConsumerShowsItsProcessedMessagesAndStatus() throws Exception {
		String id = createConsumer("proc");
		act("consumers", id, "START");

		for (int i = 0; i < 5; i++) {
			brokerConsumers.last().deliver("m" + i);
			pass(100);
		}

		mockMvc.perform(get(BASE + "/consumers"))
			.andExpect(jsonPath("$[0].status").value("RUNNING"))
			.andExpect(jsonPath("$[0].processed").value(5))
			.andExpect(jsonPath("$[0].failed").value(0));
	}

	@Test
	void whenTheQueueIsDeletedTheConsumerShowsAsFailedWithItsMessage() throws Exception {
		String id = createConsumer("proc");
		act("consumers", id, "START");

		brokerConsumers.last().brokerStops(com.messaginglab.model.StopReason.QUEUE_DELETED);

		mockMvc.perform(get(BASE + "/consumers"))
			.andExpect(jsonPath("$[0].status").value("FAILED"))
			.andExpect(jsonPath("$[0].stopMessage").value("La cola «cocina» se ha eliminado: el consumidor simulado se ha parado."));
	}

}
