package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exchanges contra un RabbitMQ real: servicio y adaptador juntos. */
class ExchangesIT {

	private final ExchangeService service = new ExchangeService(
			new RabbitMqTopology(new ManagementApiClient(RabbitMqContainerSupport.properties())));

	private final List<String> created = new ArrayList<>();

	@AfterEach
	void deleteWhatTheTestCreated() {
		for (String name : created) {
			try {
				service.delete(name);
			}
			catch (NotFoundException alreadyDeleted) {
				// la propia prueba ya lo eliminó
			}
		}
	}

	@ParameterizedTest
	@EnumSource(ExchangeType.class)
	void createsADurableExchangeOfEachType(ExchangeType type) throws Exception {
		String name = unique("tipo");

		Creation<Exchange> creation = service.create(name, type);

		assertThat(creation.created()).isTrue();
		assertThat(service.list()).contains(new Exchange(name, type, false));
		HttpResponse<String> details = RabbitMqContainerSupport.managementGet("/api/exchanges/%2F/" + encode(name));
		assertThat(details.statusCode()).isEqualTo(200);
		assertThat(details.body()).contains("\"durable\":true").contains("\"type\":\"" + type.name().toLowerCase() + "\"");
	}

	@Test
	void creatingAnIdenticalExchangeAgainDoesNotDuplicateIt() {
		String name = unique("repetido");
		service.create(name, ExchangeType.TOPIC);

		Creation<Exchange> second = service.create(name, ExchangeType.TOPIC);

		assertThat(second.created()).isFalse();
		assertThat(service.list().stream().filter(exchange -> exchange.name().equals(name))).hasSize(1);
	}

	@Test
	void creatingItAgainWithAnotherTypeIsAConflictAndKeepsTheOriginal() {
		String name = unique("conflicto");
		service.create(name, ExchangeType.TOPIC);

		assertThatThrownBy(() -> service.create(name, ExchangeType.DIRECT)).isInstanceOf(ConflictException.class)
			.hasMessage("Ya existe con ese nombre pero con otra configuración (difiere en el tipo).");

		assertThat(service.list()).contains(new Exchange(name, ExchangeType.TOPIC, false));
	}

	@Test
	void deletesAnExchangeAndDeletingItAgainIsNotFound() {
		String name = unique("borrar");
		service.create(name, ExchangeType.FANOUT);

		service.delete(name);

		assertThat(service.list().stream().map(Exchange::name)).doesNotContain(name);
		assertThatThrownBy(() -> service.delete(name)).isInstanceOf(NotFoundException.class)
			.hasMessage("El exchange «" + name + "» no existe.");
	}

	@Test
	void listsThePredefinedExchangesAfterTheOnesTheUserCreated() {
		String name = unique("orden");
		service.create(name, ExchangeType.DIRECT);

		List<Exchange> exchanges = service.list();

		assertThat(exchanges).contains(new Exchange("", ExchangeType.DIRECT, true),
				new Exchange("amq.direct", ExchangeType.DIRECT, true),
				new Exchange("amq.fanout", ExchangeType.FANOUT, true),
				new Exchange("amq.topic", ExchangeType.TOPIC, true));
		assertThat(exchanges.stream().map(Exchange::name)).doesNotContain("amq.headers", "amq.match");
		int firstPredefined = exchanges.indexOf(exchanges.stream().filter(Exchange::predefined).findFirst().orElseThrow());
		assertThat(exchanges.subList(0, firstPredefined)).noneMatch(Exchange::predefined);
		assertThat(exchanges.subList(firstPredefined, exchanges.size())).allMatch(Exchange::predefined);
	}

	@Test
	void thePredefinedExchangesCannotBeDeleted() {
		for (String name : new String[] { "", "amq.direct", "amq.fanout", "amq.topic" }) {
			assertThatThrownBy(() -> service.delete(name)).isInstanceOf(ValidationException.class);
		}
		assertThat(service.list().stream().filter(Exchange::predefined)).hasSizeGreaterThanOrEqualTo(4);
	}

	@Test
	void namesWithSlashesPercentSignsSpacesAndNonAsciiCharactersWork() throws Exception {
		String name = "a/b%c ñ€ " + UUID.randomUUID();

		service.create(name, ExchangeType.DIRECT);
		created.add(name);

		assertThat(service.list()).contains(new Exchange(name, ExchangeType.DIRECT, false));
		assertThat(RabbitMqContainerSupport.managementGet("/api/exchanges/%2F/" + encode(name)).statusCode())
			.isEqualTo(200);
		service.delete(name);
		assertThat(service.list().stream().map(Exchange::name)).doesNotContain(name);
	}

	/** Nombre propio de esta prueba: el RabbitMQ de las pruebas de integración se comparte. */
	private String unique(String base) {
		String name = "it-" + base + "-" + UUID.randomUUID();
		created.add(name);
		return name;
	}

	private static String encode(String name) {
		return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
	}

}
