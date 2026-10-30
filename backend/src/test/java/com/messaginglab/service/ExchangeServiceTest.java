package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.support.InMemoryBrokerTopology;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExchangeServiceTest {

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private final ExchangeService service = new ExchangeService(broker);

	@ParameterizedTest
	@EnumSource(ExchangeType.class)
	void createsAnExchangeOfEachType(ExchangeType type) {
		Creation<Exchange> creation = service.create("pedidos", type);

		assertThat(creation.created()).isTrue();
		assertThat(creation.value()).isEqualTo(new Exchange("pedidos", type, false));
		assertThat(service.list()).contains(new Exchange("pedidos", type, false));
	}

	@Test
	void creatingAnIdenticalExchangeDoesNotDuplicateIt() {
		service.create("pedidos", ExchangeType.TOPIC);

		Creation<Exchange> second = service.create("pedidos", ExchangeType.TOPIC);

		assertThat(second.created()).isFalse();
		assertThat(service.list().stream().filter(e -> e.name().equals("pedidos"))).hasSize(1);
	}

	@Test
	void creatingAnExchangeWithAnotherTypeIsAConflictAndKeepsTheExistingOne() {
		service.create("pedidos", ExchangeType.TOPIC);

		assertThatThrownBy(() -> service.create("pedidos", ExchangeType.DIRECT))
			.isInstanceOf(ConflictException.class);

		assertThat(service.list()).contains(new Exchange("pedidos", ExchangeType.TOPIC, false));
	}

	@Test
	void rejectsInvalidNamesWithoutTouchingTheBroker() {
		for (String name : new String[] { null, "", "   ", "a".repeat(256), "amq.mio", "a\nb" }) {
			assertThatThrownBy(() -> service.create(name, ExchangeType.DIRECT)).isInstanceOf(ValidationException.class);
		}
		assertThat(service.list()).allMatch(Exchange::predefined);
	}

	@Test
	void rejectsAMissingType() {
		assertThatThrownBy(() -> service.create("pedidos", null)).isInstanceOf(ValidationException.class)
			.hasMessage("Indica el tipo del exchange: direct, fanout o topic.");
	}

	@Test
	void listsUserExchangesFirstAndPredefinedAfterwardsEachByName() {
		service.create("zeta", ExchangeType.FANOUT);
		service.create("alfa", ExchangeType.DIRECT);

		List<Exchange> exchanges = service.list();

		assertThat(exchanges.stream().map(Exchange::name)).containsExactly("alfa", "zeta", "", "amq.direct",
				"amq.fanout", "amq.topic");
		assertThat(exchanges.stream().filter(Exchange::predefined)).hasSize(4);
	}

	@Test
	void deletesAnExchange() {
		service.create("pedidos", ExchangeType.DIRECT);

		service.delete("pedidos");

		assertThat(service.list().stream().map(Exchange::name)).doesNotContain("pedidos");
	}

	@Test
	void deletingAMissingExchangeIsNotFound() {
		assertThatThrownBy(() -> service.delete("no-existe")).isInstanceOf(NotFoundException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "amq.direct", "amq.topic", "amq.cualquiera" })
	void predefinedExchangesCannotBeDeleted(String name) {
		assertThatThrownBy(() -> service.delete(name)).isInstanceOf(ValidationException.class)
			.hasMessage("Los exchanges predefinidos de RabbitMQ no se pueden eliminar.");
		assertThat(service.list()).hasSize(4);
	}

	@Test
	void deletingWithoutANameIsAValidationError() {
		assertThatThrownBy(() -> service.delete(null)).isInstanceOf(ValidationException.class)
			.hasMessage("Indica el nombre del exchange.");
	}

}
