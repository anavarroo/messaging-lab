package com.messaginglab.assistant;

import static com.messaginglab.assistant.SnapshotTestData.QUIET;
import static com.messaginglab.assistant.SnapshotTestData.TRACE_ON;
import static com.messaginglab.assistant.SnapshotTestData.UP;
import static com.messaginglab.assistant.SnapshotTestData.consumer;
import static com.messaginglab.assistant.SnapshotTestData.emitter;
import static com.messaginglab.assistant.SnapshotTestData.exchange;
import static com.messaginglab.assistant.SnapshotTestData.queue;
import static com.messaginglab.assistant.SnapshotTestData.snapshot;
import static com.messaginglab.assistant.SnapshotTestData.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.assistant.Snapshot.Broker;
import com.messaginglab.assistant.Snapshot.Load;
import com.messaginglab.assistant.Snapshot.Queue;
import com.messaginglab.assistant.Snapshot.Run;
import com.messaginglab.assistant.Snapshot.Trace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

	private static final int BUDGET = 1_000;

	private final ContextBuilder builder = new ContextBuilder(new KnowledgeBase());

	private final QuestionClassifier classifier = new QuestionClassifier();

	private String build(Snapshot snapshot, String question) {
		return builder.build(snapshot, classifier.classify(question), question, BUDGET);
	}

	private static Snapshot growing() {
		return snapshot(List.of(queue("cocina", 480, 1, 11.0, 5.0), queue("barra", 0, 1, 0.0, 0.0)),
				List.of(emitter("Brazo robótico", Run.RUNNING, 10, 0, null)), List.of(consumer("lector-1", "cocina", Run.RUNNING, null)));
	}

	@Test
	void putsTheConclusionsFirstThenTheData() {
		String context = build(growing(), "¿Por qué crece mi cola cocina?");

		assertThat(context).startsWith("CONCLUSIONES:");
		assertThat(context.indexOf("CONCLUSIONES:")).isLessThan(context.indexOf("DATOS"));
		assertThat(context).contains("CAUSA: la cola «cocina» crece porque entran 11 mensajes por segundo y salen 5");
	}

	@Test
	void givesTheQueueDataForAQueueQuestion() {
		String context = build(growing(), "¿Cómo va la cola cocina?");

		assertThat(context).contains("- cola «cocina»: 480 listos, 0 pendientes de confirmar, 1 consumidor, entran 11/s y salen 5/s");
	}

	@Test
	void onlyIncludesTheDataOfTheTopicsAskedAbout() {
		String context = build(growing(), "¿Cómo va la cola cocina?");

		assertThat(context).doesNotContain("emisor «Brazo robótico»").doesNotContain("consumidor simulado");
	}

	@Test
	void anEmitterQuestionGetsEmittersAndTheLimit() {
		String context = build(growing(), "¿Qué emisores hay en marcha?");

		assertThat(context).contains("Emisores en marcha: 1 de 1. Suman 0 mensajes por segundo (límite 100)")
			.contains("- emisor «Brazo robótico»: en marcha, publica en cola «x», 10 por segundo");
		assertThat(context).doesNotContain("- cola «");
	}

	@Test
	void aConsumerQuestionGetsTheSimulatedConsumers() {
		assertThat(build(growing(), "¿Qué consumidor lee de cocina?")).contains("consumidor simulado «lector-1»: en marcha, lee de la cola «cocina»");
	}

	@Test
	void anExchangeQuestionShowsTheirBindings() {
		Snapshot withExchanges = new Snapshot(Instant.parse("2026-10-31T10:00:00Z"), UP, TRACE_ON, List.of(), List.of(
				exchange("pizzeria", "topic", "cocina", "barra"), exchange("avisos", "fanout")), List.of(), List.of(), QUIET);

		String context = build(withExchanges, "¿A qué colas va el exchange pizzeria?");

		assertThat(context).contains("- exchange «pizzeria» (topic): enlazado a «cocina», «barra»").contains("«avisos» (fanout): sin bindings");
		assertThat(context.indexOf("pizzeria»")).isLessThan(context.indexOf("avisos»"));
	}

	@Test
	void aGeneralQuestionGetsASummaryOfEverything() {
		String context = build(growing(), "¿Todo bien?");

		assertThat(context).contains("RabbitMQ: levantado (versión 4.4.0)").contains("Hay 2 colas").contains("- cola «cocina»")
			.contains("emisor «Brazo robótico»").contains("consumidor simulado «lector-1»");
	}

	@Test
	void saysSoWhenThereIsNothingWrongWithWhatIsAsked() {
		Snapshot calm = snapshot(List.of(queue("cocina", 0, 1, 1.0, 1.0)), List.of(), List.of());

		assertThat(build(calm, "¿Cómo va la cola cocina?")).contains("No se ha detectado ningún problema en lo que preguntas.");
	}

	@Test
	void aBrokerThatIsDownIsAlwaysReportedEvenIfTheQuestionWasAboutSomethingElse() {
		Snapshot down = with(new Broker(false, null, "sin respuesta"), new Trace(Trace.State.UNKNOWN, null), QUIET);

		String context = build(down, "¿Qué es un binding?");

		assertThat(context).contains("CAUSA: RabbitMQ no está disponible (sin respuesta)").contains("RabbitMQ: no disponible");
	}

	@Test
	void aTraceQuestionStatesTheTraceAndItsFix() {
		Snapshot off = with(UP, new Trace(Trace.State.DISABLED, "Ejecuta «rabbitmqctl trace_on»"), QUIET);

		String context = build(off, "¿Por qué no veo nada en Flujo?");

		assertThat(context).contains("Trazado de RabbitMQ: desactivado (tracing: false)").contains("SOLUCIÓN: Ejecuta «rabbitmqctl trace_on»");
	}

	@Test
	void aConceptQuestionIncludesTheManualCards() {
		String context = build(growing(), "¿Qué es un binding?");

		assertThat(context).contains("MANUAL:").contains("«Binding»: Un binding es la regla que une un exchange con una cola");
	}

	@Test
	void anActionRequestPutsWhereItIsDoneFirst() {
		String context = build(growing(), "Para todos los emisores ahora mismo.");

		assertThat(context).startsWith("PETICIÓN: el usuario pide hacer un cambio. NO PUEDES HACERLO")
			.contains("MANUAL:\n- «Dónde se hace cada cosa»: El asistente no puede hacer cambios. Parar todos los emisores")
			.contains("«Parar todo» en «Generador»");
		assertThat(context).as("los datos sobran para una petición").doesNotContain("CONCLUSIONES").doesNotContain("- cola «");
	}

	@Test
	void anActionRequestStillWarnsWhenRabbitMqIsDown() {
		Snapshot down = with(new Broker(false, null, "sin respuesta"), new Trace(Trace.State.UNKNOWN, null), QUIET);

		assertThat(build(down, "Borra la cola barra")).contains("CAUSA: RabbitMQ no está disponible");
	}

	@Test
	void aQuestionWithoutCardsHasNoManualSection() {
		assertThat(build(growing(), "¿Todo bien?")).doesNotContain("MANUAL:");
	}

	@Test
	void theQueueTheQuestionNamesComesBeforeTheFullerOnes() {
		Snapshot many = snapshot(List.of(queue("llena", 900, 1, null, null), queue("pequeña", 2, 1, null, null)), List.of(), List.of());

		String context = build(many, "¿Y la cola pequeña?");

		assertThat(context.indexOf("«pequeña»")).isLessThan(context.indexOf("«llena»"));
	}

	@Test
	void neverExceedsTheBudgetNoMatterHowManyQueuesThereAre() {
		List<Queue> queues = new ArrayList<>(IntStream.range(0, 400).mapToObj(i -> queue("cola-" + i, i, 1, 1.0, 1.0)).toList());
		Snapshot huge = snapshot(queues, List.of(), List.of());

		for (int budget : new int[] { 300, 600, 1_000, 1_500 }) {
			String context = builder.build(huge, EnumSet.of(Topic.QUEUES, Topic.STATE), "¿Cómo van las colas?", budget);

			assertThat(ContextBuilder.tokens(context)).as("presupuesto " + budget).isLessThanOrEqualTo(budget + 30);
		}
	}

	@Test
	void saysHowManyQueuesWereLeftOut() {
		List<Queue> queues = IntStream.range(0, 400).mapToObj(i -> queue("cola-" + i, i, 1, 1.0, 1.0)).toList();

		String context = builder.build(snapshot(queues, List.of(), List.of()), Set.of(Topic.QUEUES), "colas", 500);

		assertThat(context).containsPattern("\\(y \\d+ colas más, sin detalle por falta de espacio\\)");
	}

	@Test
	void keepsAllTheConclusionsEvenWhenTheDataDoesNotFit() {
		List<Queue> queues = new ArrayList<>(IntStream.range(0, 400).mapToObj(i -> queue("cola-" + i, i, 1, 1.0, 1.0)).toList());
		queues.add(queue("barra", 350, 0, 1.0, 0.0));

		String context = builder.build(snapshot(queues, List.of(), List.of()), Set.of(Topic.QUEUES), "¿Qué pasa con las colas?", 400);

		assertThat(context).contains("la cola «barra» no se vacía porque NO TIENE NINGÚN CONSUMIDOR");
	}

	@Test
	void neverMoreThanSixConclusions() {
		List<Queue> queues = IntStream.range(0, 20).mapToObj(i -> queue("sin-" + i, 100, 0, 0.0, 0.0)).toList();

		String context = builder.build(snapshot(queues, List.of(), List.of()), Set.of(Topic.QUEUES), "colas", 4_000);

		assertThat(context.lines().filter(line -> line.startsWith("- CAUSA")).count()).isEqualTo(ContextBuilder.MAX_FINDINGS);
	}

	@Test
	void usesTheNamesAsDataBetweenGuillemetsAndCarriesNoMessageBodies() {
		String context = build(growing(), "¿Todo bien?");

		assertThat(context).contains("«cocina»", "«Brazo robótico»").doesNotContain("body").doesNotContain("cuerpo:");
	}

	@Test
	void writesTheHourOfThePhoto() {
		assertThat(build(growing(), "¿Todo bien?")).contains("DATOS (a las 10:00:00 UTC):");
	}

	@Test
	void flowQuestionIncludesTheRatesAndTheOmitted() {
		Snapshot busy = with(UP, TRACE_ON, new Load(95, 100, 450));

		String context = build(busy, "¿Cuántos mensajes por segundo hay?");

		assertThat(context).contains("suman 95 mensajes por segundo; se omiten 450 eventos por segundo");
	}

	@Test
	void tokenEstimateIsConservative() {
		assertThat(ContextBuilder.tokens("")).isZero();
		assertThat(ContextBuilder.tokens("abc")).isEqualTo(1);
		assertThat(ContextBuilder.tokens("abcd")).isEqualTo(2);
	}

}
