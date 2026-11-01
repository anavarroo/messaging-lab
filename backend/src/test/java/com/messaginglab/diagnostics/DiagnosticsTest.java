package com.messaginglab.diagnostics;

import static com.messaginglab.diagnostics.SnapshotTestData.QUIET;
import static com.messaginglab.diagnostics.SnapshotTestData.TRACE_ON;
import static com.messaginglab.diagnostics.SnapshotTestData.UP;
import static com.messaginglab.diagnostics.SnapshotTestData.consumer;
import static com.messaginglab.diagnostics.SnapshotTestData.emitter;
import static com.messaginglab.diagnostics.SnapshotTestData.queue;
import static com.messaginglab.diagnostics.SnapshotTestData.queueWithDeadLetter;
import static com.messaginglab.diagnostics.SnapshotTestData.snapshot;
import static com.messaginglab.diagnostics.SnapshotTestData.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.diagnostics.Finding.Severity;
import com.messaginglab.diagnostics.Snapshot.Broker;
import com.messaginglab.diagnostics.Snapshot.Load;
import com.messaginglab.diagnostics.Snapshot.Run;
import com.messaginglab.diagnostics.Snapshot.Trace;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

	private static List<Finding> analyze(Snapshot snapshot) {
		return Diagnostics.analyze(snapshot);
	}

	private static List<String> texts(List<Finding> findings) {
		return findings.stream().map(Finding::text).toList();
	}

	@Test
	void aHealthyLaboratoryHasNothingToReport() {
		Snapshot calm = snapshot(List.of(queue("cocina", 0, 1, 2.0, 2.0)), List.of(emitter("e", Run.RUNNING, 2, 0, null)),
				List.of(consumer("c", "cocina", Run.RUNNING, null)));

		assertThat(analyze(calm)).isEmpty();
	}

	@Test
	void brokerDownIsCriticalAndExplainsWhatToDo() {
		List<Finding> findings = analyze(with(new Broker(false, null, "conexión rechazada"), new Trace(Trace.State.UNKNOWN, null), QUIET));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.CRITICAL);
		assertThat(findings.get(0).topic()).isEqualTo(Topic.STATE);
		assertThat(findings.get(0).text()).contains("CAUSA: RabbitMQ no está disponible (conexión rechazada)")
			.contains("SOLUCIÓN").contains("«Volver a comprobar»");
	}

	@Test
	void doesNotRepeatAReasonThatAlreadySaysItIsUnavailable() {
		String text = analyze(with(new Broker(false, null, "RabbitMQ no está disponible."), new Trace(Trace.State.UNKNOWN, null), QUIET)).get(0).text();

		assertThat(text).startsWith("CAUSA: RabbitMQ no está disponible, así que");
	}

	@Test
	void whenBrokerIsDownNothingElseIsReported() {
		Snapshot down = new Snapshot(snapshot(List.of(), List.of(), List.of()).time(), new Broker(false, null, null),
				new Trace(Trace.State.UNKNOWN, null), List.of(queue("x", 500, 0, null, null)),
				List.of(), List.of(emitter("e", Run.FAILED, 0, 3, "algo")), List.of(), QUIET);

		assertThat(analyze(down)).hasSize(1).first().extracting(Finding::topic).isEqualTo(Topic.STATE);
	}

	@Test
	void disabledTraceExplainsItsEffectAndHowToEnableIt() {
		List<Finding> findings = analyze(with(UP, new Trace(Trace.State.DISABLED, "Ejecuta «rabbitmqctl trace_on»"), QUIET));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).topic()).isEqualTo(Topic.TRACE);
		assertThat(findings.get(0).text()).contains("tracing: false").contains("«Flujo»").contains("SOLUCIÓN: Ejecuta «rabbitmqctl trace_on»");
	}

	@Test
	void disabledTraceWithoutHintStillSaysHowToEnableIt() {
		assertThat(analyze(with(UP, new Trace(Trace.State.DISABLED, null), QUIET)).get(0).text()).contains("rabbitmqctl trace_on");
	}

	@Test
	void anUnknownTraceIsNotReported() {
		assertThat(analyze(with(UP, new Trace(Trace.State.UNKNOWN, null), QUIET))).isEmpty();
	}

	@Test
	void aGrowingQueueSaysWhyInWholeSentences() {
		List<Finding> findings = analyze(snapshot(List.of(queue("cocina", 480, 1, 11.0, 5.0)), List.of(), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).text()).isEqualTo("CAUSA: la cola «cocina» crece porque entran 11 mensajes por segundo y salen 5; "
				+ "la diferencia son 6 más cada segundo. Ahora tiene 480 mensajes listos. SOLUCIÓN: para o ralentiza algún "
				+ "emisor, o añade otro consumidor a esa cola.");
		assertThat(findings.get(0).severity()).isEqualTo(Severity.WARNING);
	}

	@Test
	void aSlowlyGrowingQueueBelowTheThresholdIsNotReported() {
		assertThat(analyze(snapshot(List.of(queue("cocina", 5, 1, 5.5, 5.0)), List.of(), List.of()))).isEmpty();
	}

	@Test
	void decimalsAreWrittenWithACommaAndOneDecimal() {
		String text = analyze(snapshot(List.of(queue("q", 50, 1, 10.5, 4.0)), List.of(), List.of())).get(0).text();

		assertThat(text).contains("entran 10,5 mensajes por segundo").contains("salen 4").contains("6,5 más cada segundo");
	}

	@Test
	void aQueueThatDrainsSaysHowLongItWillTake() {
		List<Finding> findings = analyze(snapshot(List.of(queue("barra", 100, 1, 1.0, 11.0)), List.of(), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
		assertThat(findings.get(0).text()).contains("«barra» se está vaciando").contains("estará vacía en unos 10 segundos");
	}

	@Test
	void aNearlyEmptyQueueThatDrainsIsNotWorthMentioning() {
		assertThat(analyze(snapshot(List.of(queue("barra", 3, 1, 0.0, 5.0)), List.of(), List.of()))).isEmpty();
	}

	@Test
	void aQueueWithoutConsumersSaysItInTheCause() {
		List<Finding> findings = analyze(snapshot(List.of(queue("barra", 350, 0, 1.0, 0.0)), List.of(), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).text()).startsWith("CAUSA: la cola «barra» no se vacía porque NO TIENE NINGÚN CONSUMIDOR")
			.contains("350 mensajes listos").contains("«Consumidores»").contains("«Generador»");
	}

	@Test
	void anEmptyQueueWithoutConsumersIsNormal() {
		assertThat(analyze(snapshot(List.of(queue("barra", 0, 0, null, null)), List.of(), List.of()))).isEmpty();
	}

	@Test
	void aQueueWhoseConsumersAreUnknownIsNeverAccusedOfHavingNone() {
		assertThat(analyze(snapshot(List.of(queue("barra", 350, -1, null, null)), List.of(), List.of()))).isEmpty();
	}

	@Test
	void aQueueWithoutConsumersIsNotAlsoSaidToGrow() {
		List<Finding> findings = analyze(snapshot(List.of(queue("barra", 350, 0, 5.0, 0.0)), List.of(), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).text()).contains("NO TIENE NINGÚN CONSUMIDOR");
	}

	@Test
	void unknownRatesProduceNoGrowthConclusion() {
		assertThat(analyze(snapshot(List.of(queue("cocina", 480, 1, null, null)), List.of(), List.of()))).isEmpty();
	}

	@Test
	void aDeadLetterQueueWithMessagesNamesWhereTheyCameFrom() {
		List<Finding> findings = analyze(snapshot(
				List.of(queueWithDeadLetter("cocina", 0, "cocina-fallidos"), queue("cocina-fallidos", 82, 0, null, null)),
				List.of(), List.of()));

		assertThat(texts(findings)).hasSize(1);
		assertThat(findings.get(0).text()).contains("dead-letter queue «cocina-fallidos» tiene 82 mensajes listos")
			.contains("«cocina» rechazó sin reencolar").contains("«Inspeccionar»");
	}

	@Test
	void aDeadLetterQueueWithoutConsumersIsNotAccusedOfIt() {
		List<Finding> findings = analyze(snapshot(List.of(queueWithDeadLetter("cocina", 0, "dlq"), queue("dlq", 5, 0, null, null)),
				List.of(), List.of()));

		assertThat(texts(findings)).noneMatch(text -> text.contains("NINGÚN CONSUMIDOR"));
	}

	@Test
	void anEmptyDeadLetterQueueIsNotReported() {
		assertThat(analyze(snapshot(List.of(queueWithDeadLetter("cocina", 0, "dlq"), queue("dlq", 0, 0, null, null)), List.of(),
				List.of()))).isEmpty();
	}

	@Test
	void aFailedEmitterIsCriticalWithItsReason() {
		List<Finding> findings = analyze(snapshot(List.of(), List.of(emitter("Brazo", Run.FAILED, 0, 0, "La cola «x» ya no existe.")), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.CRITICAL);
		assertThat(findings.get(0).text()).contains("el emisor «Brazo» se paró por un error: La cola «x» ya no existe.")
			.contains("«Iniciar»");
	}

	@Test
	void aFailedEmitterWithoutMessageSaysSo() {
		assertThat(analyze(snapshot(List.of(), List.of(emitter("e", Run.FAILED, 0, 0, null)), List.of())).get(0).text())
			.contains("no se indicó el motivo");
	}

	@Test
	void anEmitterThatFinishedByItsLimitIsInformative() {
		List<Finding> findings = analyze(snapshot(List.of(), List.of(emitter("e", Run.FINISHED, 0, 0, "Ha publicado los 100 mensajes pedidos.")), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
		assertThat(findings.get(0).text()).contains("terminó por sí solo");
	}

	@Test
	void unroutedMessagesPointToTheBindings() {
		List<Finding> findings = analyze(snapshot(List.of(), List.of(emitter("Sensor", Run.RUNNING, 1, 12, null)), List.of()));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).text()).contains("12 mensajes del emisor «Sensor» no llegaron a ninguna cola").contains("«Bindings»");
	}

	@Test
	void aFailedSimulatedConsumerIsCritical() {
		List<Finding> findings = analyze(snapshot(List.of(), List.of(), List.of(consumer("lector", "cocina", Run.FAILED, "La cola «cocina» ya no existe."))));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).topic()).isEqualTo(Topic.CONSUMERS);
		assertThat(findings.get(0).text()).contains("consumidor simulado «lector» se paró por un error");
	}

	@Test
	void theGlobalLimitIsReportedWhenAlmostReached() {
		assertThat(analyze(with(UP, TRACE_ON, new Load(95, 100, 0)))).hasSize(1);
		assertThat(analyze(with(UP, TRACE_ON, new Load(95, 100, 0))).get(0).text()).contains("usan 95 de 100");
		assertThat(analyze(with(UP, TRACE_ON, new Load(80, 100, 0)))).isEmpty();
	}

	@Test
	void omittedFlowEventsAreMentionedButSaidToBeExact() {
		List<Finding> findings = analyze(with(UP, TRACE_ON, new Load(0, 100, 450)));

		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).topic()).isEqualTo(Topic.FLOW);
		assertThat(findings.get(0).text()).contains("450 eventos por segundo").contains("siguen siendo exactos");
	}

	@Test
	void findingsComeFromMostToLeastSevere() {
		Snapshot busy = snapshot(List.of(queue("a", 100, 1, 1.0, 11.0), queue("b", 350, 0, 1.0, 0.0)),
				List.of(emitter("e", Run.FAILED, 0, 0, "x")), List.of());

		assertThat(analyze(busy)).extracting(Finding::severity)
			.containsExactly(Severity.CRITICAL, Severity.WARNING, Severity.INFO);
	}

	@Test
	void everyConclusionIsAWholeSentenceThatNamesTheThingWithGuillemets() {
		Snapshot all = snapshot(List.of(queue("q1", 400, 1, 12.0, 5.0), queue("q2", 300, 0, 1.0, 0.0)),
				List.of(emitter("e", Run.FAILED, 0, 4, "mal")), List.of(consumer("c", "q1", Run.FAILED, "mal")));

		assertThat(analyze(all)).allSatisfy(finding -> {
			assertThat(finding.text()).contains("«");
			assertThat(finding.text()).endsWith(".");
		});
	}

}
