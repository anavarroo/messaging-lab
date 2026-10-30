package com.messaginglab.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.EmitterSnapshot;
import com.messaginglab.generator.EmitterSpec;
import com.messaginglab.generator.EmitterStatus;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.Interval;
import com.messaginglab.generator.rabbitmq.RabbitMqDestination;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot.Status;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec.FailureAction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqGeneratorController.class)
class RabbitMqGeneratorControllerTest {

	private static final String BASE = "/api/rabbitmq/generator";

	private static final String EMITTER_JSON = """
			{"name":"robot","destination":{"type":"EXCHANGE","exchange":"pizzeria","routingKey":"pedido.pizza"},
			 "interval":{"mode":"FIXED","fixedMs":500},"template":"{\\"n\\": {{contador}}}",
			 "maxMessages":100,"maxDurationSeconds":60,"onlyWhenChanged":true}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EmitterService emitters;

	@MockitoBean
	private SimulatedConsumerService consumers;

	private static EmitterSnapshot snapshot(EmitterStatus status) {
		EmitterSpec spec = new EmitterSpec("robot", RabbitMqDestination.toExchange("pizzeria", "pedido.pizza"),
				Interval.fixed(500), "{\"n\": {{contador}}}", 100L, 60L, true);
		return new EmitterSnapshot("e1", spec, status, 12, 2, 1.9, null);
	}

	private static SimulatedConsumerSnapshot consumerSnapshot(Status status) {
		SimulatedConsumerSpec spec = new SimulatedConsumerSpec("proc", "cocina", Interval.range(100, 300), 20,
				FailureAction.REJECT);
		return new SimulatedConsumerSnapshot("c1", spec, status, 30, 6, 3.3, null);
	}

	// ---- emisores

	@Test
	void listsTheEmittersWithTheirRealStateAndCounters() throws Exception {
		given(emitters.list()).willReturn(List.of(snapshot(EmitterStatus.RUNNING)));

		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value("e1"))
			.andExpect(jsonPath("$[0].name").value("robot"))
			.andExpect(jsonPath("$[0].status").value("RUNNING"))
			.andExpect(jsonPath("$[0].published").value(12))
			.andExpect(jsonPath("$[0].unrouted").value(2))
			.andExpect(jsonPath("$[0].ratePerSecond").value(1.9))
			.andExpect(jsonPath("$[0].destination.type").value("EXCHANGE"))
			.andExpect(jsonPath("$[0].destination.exchange").value("pizzeria"))
			.andExpect(jsonPath("$[0].destination.routingKey").value("pedido.pizza"))
			.andExpect(jsonPath("$[0].interval.mode").value("FIXED"))
			.andExpect(jsonPath("$[0].interval.fixedMs").value(500))
			.andExpect(jsonPath("$[0].maxMessages").value(100))
			.andExpect(jsonPath("$[0].maxDurationSeconds").value(60))
			.andExpect(jsonPath("$[0].onlyWhenChanged").value(true));
	}

	@Test
	void createsAnEmitterAndAnswers201TranslatingTheRequestToTheDomain() throws Exception {
		given(emitters.create(any())).willReturn(snapshot(EmitterStatus.STOPPED));

		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content(EMITTER_JSON))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("STOPPED"));

		ArgumentCaptor<EmitterSpec> captor = ArgumentCaptor.forClass(EmitterSpec.class);
		verify(emitters).create(captor.capture());
		EmitterSpec spec = captor.getValue();
		assertThat(spec.name()).isEqualTo("robot");
		assertThat(spec.destination()).isEqualTo(RabbitMqDestination.toExchange("pizzeria", "pedido.pizza"));
		assertThat(spec.interval()).isEqualTo(Interval.fixed(500));
		assertThat(spec.template()).isEqualTo("{\"n\": {{contador}}}");
		assertThat(spec.maxMessages()).isEqualTo(100L);
		assertThat(spec.maxDurationSeconds()).isEqualTo(60L);
		assertThat(spec.onlyWhenChanged()).isTrue();
	}

	@Test
	void translatesARangeFrequencyAndAQueueDestination() throws Exception {
		given(emitters.create(any())).willReturn(snapshot(EmitterStatus.STOPPED));

		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"directo","destination":{"type":"QUEUE","queue":"cocina"},
				 "interval":{"mode":"RANGE","minMs":200,"maxMs":800},"template":"x"}"""))
			.andExpect(status().isCreated());

		ArgumentCaptor<EmitterSpec> captor = ArgumentCaptor.forClass(EmitterSpec.class);
		verify(emitters).create(captor.capture());
		assertThat(captor.getValue().destination()).isEqualTo(RabbitMqDestination.toQueue("cocina"));
		assertThat(captor.getValue().interval()).isEqualTo(Interval.range(200, 800));
		assertThat(captor.getValue().maxMessages()).isNull();
		assertThat(captor.getValue().onlyWhenChanged()).isFalse();
	}

	@Test
	void showsARangeAndAQueueDestinationInTheResponse() throws Exception {
		EmitterSpec spec = new EmitterSpec("directo", RabbitMqDestination.toQueue("cocina"), Interval.range(200, 800), "x",
				null, null, false);
		EmitterSnapshot failed = new EmitterSnapshot("e2", spec, EmitterStatus.FAILED, 0, 0, 0,
				"Se ha parado porque el destino (la cola «cocina») ya no existe.");
		given(emitters.start("e2")).willReturn(failed);

		mockMvc.perform(post(BASE + "/emitters/e2/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.destination.type").value("QUEUE"))
			.andExpect(jsonPath("$.destination.queue").value("cocina"))
			.andExpect(jsonPath("$.interval.mode").value("RANGE"))
			.andExpect(jsonPath("$.interval.minMs").value(200))
			.andExpect(jsonPath("$.interval.maxMs").value(800))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.stopMessage").value("Se ha parado porque el destino (la cola «cocina») ya no existe."));
	}

	@Test
	void updatesAnEmitter() throws Exception {
		given(emitters.update(eq("e1"), any())).willReturn(snapshot(EmitterStatus.STOPPED));

		mockMvc.perform(put(BASE + "/emitters/e1").contentType(MediaType.APPLICATION_JSON).content(EMITTER_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("e1"));
	}

	@Test
	void deletesAnEmitter() throws Exception {
		mockMvc.perform(delete(BASE + "/emitters/e1")).andExpect(status().isNoContent());

		verify(emitters).delete("e1");
	}

	@Test
	void eachActionCallsTheMatchingOperationAndReturnsTheNewState() throws Exception {
		given(emitters.start("e1")).willReturn(snapshot(EmitterStatus.RUNNING));
		given(emitters.pause("e1")).willReturn(snapshot(EmitterStatus.PAUSED));
		given(emitters.resume("e1")).willReturn(snapshot(EmitterStatus.RUNNING));
		given(emitters.stop("e1")).willReturn(snapshot(EmitterStatus.STOPPED));
		String[][] cases = { { "START", "RUNNING" }, { "PAUSE", "PAUSED" }, { "RESUME", "RUNNING" }, { "STOP", "STOPPED" } };

		for (String[] test : cases) {
			mockMvc.perform(post(BASE + "/emitters/e1/actions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"" + test[0] + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value(test[1]));
		}
	}

	@Test
	void anUnknownActionIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post(BASE + "/emitters/e1/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"EXPLOTAR\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(emitters);
	}

	@Test
	void aRequestWithMissingFieldsIsRejectedNamingThem() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(emitters);
	}

	@Test
	void anInvalidFrequencyIsExplainedInSpanish() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"x","destination":{"type":"QUEUE","queue":"q"},"interval":{"mode":"FIXED","fixedMs":1},"template":"x"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Los intervalos deben estar entre 2 ms y 86400000 ms (un día)."));

		verifyNoInteractions(emitters);
	}

	@Test
	void aMissingFixedValueOrRangeIsExplained() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"x","destination":{"type":"QUEUE","queue":"q"},"interval":{"mode":"FIXED"},"template":"x"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Indica el valor fijo de la frecuencia, en milisegundos."));
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"x","destination":{"type":"QUEUE","queue":"q"},"interval":{"mode":"RANGE","minMs":10},"template":"x"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Indica el mínimo y el máximo del rango de la frecuencia, en milisegundos."));
	}

	@Test
	void aDestinationWithoutItsNameIsExplained() throws Exception {
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"x","destination":{"type":"EXCHANGE"},"interval":{"mode":"FIXED","fixedMs":10},"template":"x"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Indica el exchange de destino."));
	}

	@Test
	void serviceErrorsBecomeTheMatchingStatusWithAMessageInSpanish() throws Exception {
		given(emitters.create(any())).willThrow(new ConflictException("Ya hay un emisor con el nombre «robot»."));
		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content(EMITTER_JSON))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value("Ya hay un emisor con el nombre «robot»."));

		given(emitters.start("x")).willThrow(new NotFoundException("El emisor no existe: puede que ya se haya eliminado."));
		mockMvc.perform(post(BASE + "/emitters/x/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		given(emitters.start("y")).willThrow(new ConflictException("No se puede poner en marcha «a»: añadiría 100 mensajes por segundo…"));
		mockMvc.perform(post(BASE + "/emitters/y/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("No se puede poner en marcha «a»: añadiría 100 mensajes por segundo…"));

		given(emitters.list()).willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));
		mockMvc.perform(get(BASE + "/emitters"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

	@Test
	void aTemplateErrorFromTheServiceIsShownWithItsPlace() throws Exception {
		given(emitters.create(any())).willThrow(new ValidationException("Error en la plantilla (línea 2, columna 8): la función «x» no existe."));

		mockMvc.perform(post(BASE + "/emitters").contentType(MediaType.APPLICATION_JSON).content(EMITTER_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Error en la plantilla (línea 2, columna 8): la función «x» no existe."));
	}

	// ---- plantillas

	@Test
	void listsTheSixIndustrialTemplatesWithTheirSuggestions() throws Exception {
		mockMvc.perform(get(BASE + "/templates"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$[0].id").value("robot-ur"))
			.andExpect(jsonPath("$[0].name").value("Brazo robótico"))
			.andExpect(jsonPath("$[0].suggestedInterval.mode").value("FIXED"))
			.andExpect(jsonPath("$[0].suggestedInterval.fixedMs").value(100))
			.andExpect(jsonPath("$[0].suggestedRoutingKey").value("planta1.robot.ur5e01.estado"))
			.andExpect(jsonPath("$[0].onlyWhenChanged").value(false))
			.andExpect(jsonPath("$[4].id").value("plc-tag"))
			.andExpect(jsonPath("$[4].onlyWhenChanged").value(true))
			.andExpect(jsonPath("$[5].suggestedInterval.mode").value("RANGE"));
	}

	@Test
	void previewsATemplateWithoutSendingAnything() throws Exception {
		mockMvc.perform(post(BASE + "/templates/preview").contentType(MediaType.APPLICATION_JSON)
			.content("{\"template\":\"{\\\"n\\\": {{contador}}, \\\"t\\\": {{numero(5,5)}}}\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("{\"n\": 1, \"t\": 5}"))
			.andExpect(jsonPath("$.bytes").value(16));

		verifyNoInteractions(emitters, consumers);
	}

	@Test
	void previewingATemplateWithMultibyteTextCountsUtf8Bytes() throws Exception {
		mockMvc.perform(post(BASE + "/templates/preview").contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"ñ€\"}"))
			.andExpect(jsonPath("$.bytes").value(5));
	}

	@Test
	void aTemplatePreviewRejectsAnInvalidTemplateExplainingWhatFailsAndWhere() throws Exception {
		mockMvc.perform(post(BASE + "/templates/preview").contentType(MediaType.APPLICATION_JSON)
			.content("{\"template\":\"hola\\n{{inventada}}\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("línea 2, columna 1")))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("la función «inventada» no existe")));
	}

	@Test
	void aTemplatePreviewRejectsABodyOver64Kb() throws Exception {
		mockMvc.perform(post(BASE + "/templates/preview").contentType(MediaType.APPLICATION_JSON)
			.content("{\"template\":\"" + "x".repeat(70_000) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("64 KB")));
	}

	// ---- ajustes

	@Test
	void returnsTheSettingsAndTheCurrentUse() throws Exception {
		given(emitters.limits()).willReturn(new GeneratorLimits());
		given(emitters.ratePerSecondInUse()).willReturn(35.5);

		mockMvc.perform(get(BASE + "/settings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maxRatePerSecond").value(100))
			.andExpect(jsonPath("$.absoluteMaxRatePerSecond").value(500))
			.andExpect(jsonPath("$.ratePerSecondInUse").value(35.5))
			.andExpect(jsonPath("$.maxEmitters").value(20))
			.andExpect(jsonPath("$.maxSimulatedConsumers").value(20));
	}

	@Test
	void changesTheLimitAndReturnsTheNewSettings() throws Exception {
		GeneratorLimits limits = new GeneratorLimits();
		given(emitters.limits()).willReturn(limits);
		org.mockito.Mockito.doAnswer(call -> {
			limits.changeMaxRate(call.getArgument(0), 0);
			return null;
		}).when(emitters).changeLimit(300);

		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":300}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maxRatePerSecond").value(300));
	}

	@Test
	void refusingALimitChangeIsExplained() throws Exception {
		org.mockito.Mockito.doThrow(new ConflictException("No se puede bajar el límite a 50 mensajes por segundo: ya hay 100 en marcha."))
			.when(emitters)
			.changeLimit(50);

		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{\"maxRatePerSecond\":50}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("No se puede bajar el límite a 50 mensajes por segundo: ya hay 100 en marcha."));
	}

	@Test
	void aMissingLimitIsRejected() throws Exception {
		mockMvc.perform(put(BASE + "/settings").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());

		verifyNoInteractions(emitters);
	}

	// ---- consumidores simulados

	@Test
	void listsTheSimulatedConsumersWithTheirCounters() throws Exception {
		given(consumers.list()).willReturn(List.of(consumerSnapshot(Status.RUNNING)));

		mockMvc.perform(get(BASE + "/consumers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value("c1"))
			.andExpect(jsonPath("$[0].name").value("proc"))
			.andExpect(jsonPath("$[0].queue").value("cocina"))
			.andExpect(jsonPath("$[0].processing.mode").value("RANGE"))
			.andExpect(jsonPath("$[0].processing.minMs").value(100))
			.andExpect(jsonPath("$[0].failurePercent").value(20))
			.andExpect(jsonPath("$[0].onFailure").value("REJECT"))
			.andExpect(jsonPath("$[0].status").value("RUNNING"))
			.andExpect(jsonPath("$[0].processed").value(30))
			.andExpect(jsonPath("$[0].failed").value(6))
			.andExpect(jsonPath("$[0].ratePerSecond").value(3.3));
	}

	@Test
	void createsASimulatedConsumerAndAnswers201() throws Exception {
		given(consumers.create(any())).willReturn(consumerSnapshot(Status.STOPPED));

		mockMvc.perform(post(BASE + "/consumers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"proc","queue":"cocina","processing":{"mode":"FIXED","fixedMs":250},"failurePercent":15,"onFailure":"REQUEUE"}"""))
			.andExpect(status().isCreated());

		ArgumentCaptor<SimulatedConsumerSpec> captor = ArgumentCaptor.forClass(SimulatedConsumerSpec.class);
		verify(consumers).create(captor.capture());
		assertThat(captor.getValue()).isEqualTo(new SimulatedConsumerSpec("proc", "cocina", Interval.fixed(250), 15, FailureAction.REQUEUE));
	}

	@Test
	void aFailurePercentageOutOfRangeIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post(BASE + "/consumers").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"proc","queue":"cocina","processing":{"mode":"FIXED","fixedMs":250},"failurePercent":150,"onFailure":"REQUEUE"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(consumers);
	}

	@Test
	void deletesASimulatedConsumer() throws Exception {
		mockMvc.perform(delete(BASE + "/consumers/c1")).andExpect(status().isNoContent());

		verify(consumers).delete("c1");
	}

	@Test
	void startsAndStopsASimulatedConsumer() throws Exception {
		given(consumers.start("c1")).willReturn(consumerSnapshot(Status.RUNNING));
		given(consumers.stop("c1")).willReturn(consumerSnapshot(Status.STOPPED));

		mockMvc.perform(post(BASE + "/consumers/c1/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RUNNING"));
		mockMvc.perform(post(BASE + "/consumers/c1/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"STOP\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STOPPED"));
	}

	@Test
	void aSimulatedConsumerDoesNotAcceptPauseOrResume() throws Exception {
		for (String action : List.of("PAUSE", "RESUME")) {
			mockMvc.perform(post(BASE + "/consumers/c1/actions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"" + action + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Los consumidores simulados solo admiten las acciones START y STOP."));
		}
	}

	@Test
	void aMissingQueueWhenStartingAConsumerIsNotFound() throws Exception {
		given(consumers.start("c1")).willThrow(new NotFoundException("La cola «cocina» no existe."));

		mockMvc.perform(post(BASE + "/consumers/c1/actions").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("La cola «cocina» no existe."));
	}

	// ---- parar todo

	@Test
	void stopAllStopsTheEmittersAndTheSimulatedConsumers() throws Exception {
		mockMvc.perform(post(BASE + "/stop-all")).andExpect(status().isOk());

		verify(emitters).stopAll();
		verify(consumers).stopAll();
	}

}
