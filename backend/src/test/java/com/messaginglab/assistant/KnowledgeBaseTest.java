package com.messaginglab.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.assistant.KnowledgeBase.Card;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeBaseTest {

	private final KnowledgeBase manual = new KnowledgeBase();

	@Test
	void hasACardForEveryConceptOfTheGuide() {
		Set<String> ids = KnowledgeBase.CARDS.stream().map(Card::id).collect(Collectors.toSet());

		assertThat(ids).contains("mensaje", "productor", "exchange", "cola", "binding", "routing-key", "consumidor",
				"acknowledgement", "dead-letter", "exchange-defecto", "tipos-exchange", "sin-enrutar", "reentregado", "pendiente",
				"trazado", "flujo", "generador", "limite", "simulado", "acciones");
	}

	@Test
	void cardsAreShortSoManyFitInASmallContext() {
		assertThat(KnowledgeBase.CARDS).allSatisfy(card -> {
			assertThat(card.text().length()).as(card.id()).isBetween(80, 520);
			assertThat(card.keywords()).as(card.id()).isNotEmpty();
		});
	}

	@Test
	void cardIdsAreUniqueAndEachCardNamesTheLabWordsItUses() {
		assertThat(KnowledgeBase.CARDS.stream().map(Card::id).distinct().count()).isEqualTo(KnowledgeBase.CARDS.size());
		assertThat(KnowledgeBase.CARDS.stream().map(Card::text).collect(Collectors.joining(" "))).contains("«Colas»", "«Bindings»",
				"«Exchanges»", "«Generador»", "«Flujo»", "«Consumidores»", "«Publicación»");
	}

	@Test
	void keywordsAreWrittenWithoutAccentsOrCapitalsSoTheyCanMatch() {
		assertThat(KnowledgeBase.CARDS).allSatisfy(card -> assertThat(card.keywords())
			.allSatisfy(word -> assertThat(word).isEqualTo(Words.normalize(word))));
	}

	@Test
	void findsTheCardOfWhatIsAsked() {
		assertThat(manual.find("¿Qué es un binding?", 2)).extracting(Card::id).contains("binding");
		assertThat(manual.find("explícame las dead-letter queues", 2)).extracting(Card::id).contains("dead-letter");
		assertThat(manual.find("¿qué significa tracing: false?", 2)).extracting(Card::id).contains("trazado");
		assertThat(manual.find("¿qué es el exchange por defecto?", 3)).extracting(Card::id).contains("exchange-defecto");
	}

	@Test
	void theBestMatchesComeFirstAndNeverMoreThanAsked() {
		List<Card> found = manual.find("qué es un exchange direct, fanout o topic", 2);

		assertThat(found).hasSize(2);
		assertThat(found.get(0).id()).isEqualTo("tipos-exchange");
	}

	@Test
	void findsNothingWhenNothingMatches() {
		assertThat(manual.find("¿qué tiempo hace?", 3)).isEmpty();
		assertThat(manual.find("", 3)).isEmpty();
		assertThat(manual.find(null, 3)).isEmpty();
	}

	@Test
	void ignoresAccentsAndCase() {
		assertThat(manual.find("REENTREGADO", 1)).extracting(Card::id).containsExactly("reentregado");
		assertThat(manual.find("trazado", 1)).extracting(Card::id).containsExactly("trazado");
	}

}
