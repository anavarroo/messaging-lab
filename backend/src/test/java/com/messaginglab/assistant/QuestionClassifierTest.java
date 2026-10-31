package com.messaginglab.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class QuestionClassifierTest {

	private final QuestionClassifier classifier = new QuestionClassifier();

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"¿Por qué no se vacía la cola barra?|QUEUES",
			"¿Por qué crece mi cola cocina?|QUEUES",
			"¿Qué es un binding?|CONCEPT,EXCHANGES",
			"Explícame la diferencia entre direct y topic|CONCEPT,EXCHANGES",
			"Para todos los emisores ahora mismo|EMITTERS",
			"¿Mi emisor está publicando?|EMITTERS",
			"¿Por qué no veo nada en la pestaña Flujo?|FLOW,TRACE",
			"¿Está activo el trazado?|TRACE",
			"¿Funciona RabbitMQ? ¿Qué versión tiene?|STATE",
			"¿El backend está disponible?|STATE",
			"¿Cómo confirmo un mensaje?|CONSUMERS",
			"¿Qué consumidor lee de la cola cocina?|CONSUMERS,QUEUES",
			"¿Cuántos mensajes por segundo entran?|FLOW",
			"¿Cuál es el límite del generador?|EMITTERS",
			"¿Dónde veo los mensajes rechazados?|CONSUMERS",
			"¿Qué hay en la dead-letter queue?|QUEUES",
			"¿Por qué hay mensajes sin enrutar?|EMITTERS,EXCHANGES",
			"Para todos los emisores ahora mismo.|ACTION,EMITTERS",
			"Borra la cola barra.|ACTION,QUEUES",
			"¿Puedes vaciar la cola cocina?|ACTION,QUEUES",
			"Quiero que pares el generador|ACTION,EMITTERS",
	})
	void assignsTheTopicsOfTheQuestion(String question, String expected) {
		Set<Topic> topics = classifier.classify(question);

		for (String topic : expected.split(",")) {
			assertThat(topics).contains(Topic.valueOf(topic));
		}
	}

	@Test
	void anAskAboutTheFlowAlsoLooksAtTheTraceBecauseThatIsWhereItUsuallyFails() {
		assertThat(classifier.classify("el flujo está vacío")).contains(Topic.FLOW, Topic.TRACE);
	}

	@Test
	void aQuestionIsNotMistakenForAnAction() {
		assertThat(classifier.classify("¿Para qué sirve un binding?")).doesNotContain(Topic.ACTION);
		assertThat(classifier.classify("¿Por qué crece la cola?")).doesNotContain(Topic.ACTION);
		assertThat(classifier.classify("¿Qué es parar un emisor?")).doesNotContain(Topic.ACTION);
	}

	@Test
	void recognisesQuestionsAboutTheGeneralState() {
		for (String question : new String[] { "¿Está todo bien?", "¿Hay algún problema?", "¿Qué pasa?", "¿Cómo va el laboratorio?", "Dame un resumen" }) {
			assertThat(classifier.isStatusCheck(question)).as(question).isTrue();
		}
		for (String question : new String[] { "¿Qué tiempo hace hoy en Cádiz?", "Hola", "", null }) {
			assertThat(classifier.isStatusCheck(question)).as(String.valueOf(question)).isFalse();
		}
	}

	@Test
	void recognisesQuestionsAboutWhetherDataIsFlowing() {
		for (String question : new String[] { "¿Hay algo pasando datos?", "¿Hay tráfico?", "¿Se mueve algo?", "¿Hay actividad ahora?", "¿Se está publicando algo?" }) {
			assertThat(classifier.isActivityCheck(question)).as(question).isTrue();
		}
		assertThat(classifier.isActivityCheck("¿Qué es un binding?")).isFalse();
	}

	@Test
	void ignoresCaseAndAccents() {
		assertThat(classifier.classify("TRAZADO")).contains(Topic.TRACE);
		assertThat(classifier.classify("traZADO")).contains(Topic.TRACE);
		assertThat(classifier.classify("¿QUÉ ES UN EXCHANGE?")).contains(Topic.CONCEPT, Topic.EXCHANGES);
	}

	@Test
	void aQuestionThatMatchesNothingGetsAGeneralSummary() {
		assertThat(classifier.classify("¿Todo bien?")).containsExactlyInAnyOrder(Topic.STATE, Topic.QUEUES, Topic.EMITTERS, Topic.CONSUMERS);
	}

	@Test
	void emptyOrNullQuestionsAlsoGetTheSummary() {
		assertThat(classifier.classify("")).hasSize(4);
		assertThat(classifier.classify(null)).hasSize(4);
		assertThat(classifier.classify("   ")).hasSize(4);
	}

	@Test
	void aQuestionCanBeAboutSeveralThings() {
		assertThat(classifier.classify("¿Por qué mi emisor no llena la cola y no veo el flujo?"))
			.contains(Topic.EMITTERS, Topic.QUEUES, Topic.FLOW);
	}

}
