package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Binding;
import com.messaginglab.model.Creation;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.support.InMemoryBrokerTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BindingServiceTest {

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private final BindingService service = new BindingService(broker);

	@BeforeEach
	void setUp() {
		broker.createExchange("directo", ExchangeType.DIRECT);
		broker.createExchange("difusion", ExchangeType.FANOUT);
		broker.createExchange("temas", ExchangeType.TOPIC);
		broker.createQueue("cola1", null);
		broker.createQueue("cola2", null);
	}

	@Test
	void createsABindingInADirectExchange() {
		Creation<Binding> creation = service.create("directo", "cola1", "alta");

		assertThat(creation).isEqualTo(new Creation<>(new Binding("directo", "cola1", "alta"), true));
		assertThat(service.list(null, null)).containsExactly(new Binding("directo", "cola1", "alta"));
	}

	@Test
	void fanoutIgnoresTheRoutingKey() {
		Creation<Binding> creation = service.create("difusion", "cola1", "lo-que-sea");

		assertThat(creation.value().routingKey()).isEmpty();
		assertThat(creation.created()).isTrue();
	}

	@Test
	void fanoutBindingsWithDifferentKeysAreTheSameBinding() {
		service.create("difusion", "cola1", "a");

		Creation<Binding> second = service.create("difusion", "cola1", "b");

		assertThat(second.created()).isFalse();
		assertThat(service.list("difusion", null)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "pedido.creado", "pedido.*", "pedido.#", "#", "*", "*.error.#", "a.*.b" })
	void topicAcceptsValidPatterns(String pattern) {
		assertThat(service.create("temas", "cola1", pattern).created()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { ".a", "a.", "a..b", "a*", "a.b#", "x#y" })
	void topicRejectsInvalidPatternsWithoutCreatingTheBinding(String pattern) {
		assertThatThrownBy(() -> service.create("temas", "cola1", pattern)).isInstanceOf(ValidationException.class);

		assertThat(service.list(null, null)).isEmpty();
	}

	@Test
	void anEmptyOrMissingRoutingKeyIsValidInDirectAndTopic() {
		assertThat(service.create("directo", "cola1", "").value().routingKey()).isEmpty();
		assertThat(service.create("temas", "cola1", null).value().routingKey()).isEmpty();
	}

	@Test
	void rejectsARoutingKeyOfMoreThan255CharactersInDirectAndTopic() {
		String tooLong = "a".repeat(256);

		assertThatThrownBy(() -> service.create("directo", "cola1", tooLong)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("La routing key");
		assertThatThrownBy(() -> service.create("temas", "cola1", tooLong)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("El patrón");
		assertThat(service.create("directo", "cola1", "a".repeat(255)).created()).isTrue();
	}

	@Test
	void theDefaultExchangeDoesNotAcceptBindings() {
		assertThatThrownBy(() -> service.create("", "cola1", "cola1")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("exchange por defecto");
		assertThatThrownBy(() -> service.delete("", "cola1", "cola1")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("exchange por defecto");
	}

	@Test
	void theExchangeMustExist() {
		assertThatThrownBy(() -> service.create("fantasma", "cola1", "a")).isInstanceOf(NotFoundException.class)
			.hasMessage("El exchange «fantasma» no existe.");
	}

	@Test
	void theQueueMustExist() {
		assertThatThrownBy(() -> service.create("directo", "fantasma", "a")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «fantasma» no existe.");
	}

	@Test
	void missingNamesAreRejected() {
		assertThatThrownBy(() -> service.create(null, "cola1", "a")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create("directo", null, "a")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create("directo", "", "a")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.delete(null, "cola1", "a")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.delete("directo", null, "a")).isInstanceOf(ValidationException.class);
	}

	@Test
	void aRepeatedBindingIsReportedAsExistingWithoutDuplicatingIt() {
		service.create("directo", "cola1", "alta");

		Creation<Binding> second = service.create("directo", "cola1", "alta");

		assertThat(second.created()).isFalse();
		assertThat(service.list(null, null)).hasSize(1);
	}

	@Test
	void aQueueCanHaveSeveralBindingsWithDifferentKeysAndExchanges() {
		service.create("directo", "cola1", "alta");
		service.create("directo", "cola1", "baja");
		service.create("temas", "cola1", "a.*");
		service.create("difusion", "cola1", null);

		assertThat(service.list(null, "cola1")).hasSize(4);
	}

	@Test
	void listFiltersByExchangeAndByQueueAndIsOrdered() {
		service.create("temas", "cola2", "b");
		service.create("directo", "cola2", "z");
		service.create("directo", "cola1", "a");

		assertThat(service.list(null, null)).containsExactly(new Binding("directo", "cola1", "a"),
				new Binding("directo", "cola2", "z"), new Binding("temas", "cola2", "b"));
		assertThat(service.list("directo", null)).hasSize(2);
		assertThat(service.list(null, "cola2")).hasSize(2);
		assertThat(service.list("directo", "cola2")).containsExactly(new Binding("directo", "cola2", "z"));
	}

	@Test
	void deletesABindingIncludingOneWithEmptyKeyAndKeepsTheOthers() {
		service.create("directo", "cola1", "");
		service.create("directo", "cola1", "alta");

		service.delete("directo", "cola1", null);

		assertThat(service.list(null, null)).containsExactly(new Binding("directo", "cola1", "alta"));
	}

	@Test
	void deletingAMissingBindingIsNotFound() {
		assertThatThrownBy(() -> service.delete("directo", "cola1", "alta")).isInstanceOf(NotFoundException.class);
	}

}
