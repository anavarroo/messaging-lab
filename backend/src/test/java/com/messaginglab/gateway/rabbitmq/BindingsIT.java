package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Binding;
import com.messaginglab.model.Creation;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.service.BindingService;
import com.messaginglab.service.ExchangeService;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bindings contra un RabbitMQ real: servicios y adaptador juntos. */
class BindingsIT {

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final ExchangeService exchanges = new ExchangeService(topology);

	private final QueueService queues = new QueueService(topology);

	private final BindingService service = new BindingService(topology);

	private final List<String> createdExchanges = new ArrayList<>();

	private final List<String> createdQueues = new ArrayList<>();

	@AfterEach
	void deleteWhatTheTestCreated() {
		// al borrar el exchange y la cola desaparecen también sus bindings
		for (String name : createdExchanges) {
			ignoreMissing(() -> exchanges.delete(name));
		}
		for (String name : createdQueues) {
			ignoreMissing(() -> queues.delete(name));
		}
	}

	@Test
	void createsABindingInEachTypeOfExchange() {
		String queue = queue("tipos");
		String direct = exchange("directo", ExchangeType.DIRECT);
		String fanout = exchange("difusion", ExchangeType.FANOUT);
		String topic = exchange("temas", ExchangeType.TOPIC);

		assertThat(service.create(direct, queue, "alta")).isEqualTo(new Creation<>(new Binding(direct, queue, "alta"), true));
		assertThat(service.create(fanout, queue, null)).isEqualTo(new Creation<>(new Binding(fanout, queue, ""), true));
		assertThat(service.create(topic, queue, "a.*.#")).isEqualTo(new Creation<>(new Binding(topic, queue, "a.*.#"), true));

		assertThat(service.list(null, queue)).containsExactlyInAnyOrder(new Binding(direct, queue, "alta"),
				new Binding(fanout, queue, ""), new Binding(topic, queue, "a.*.#"));
	}

	@Test
	void fanoutIgnoresTheRoutingKeyAndNeverDuplicates() {
		String queue = queue("fanout");
		String fanout = exchange("difusion", ExchangeType.FANOUT);

		service.create(fanout, queue, "ignorada");
		Creation<Binding> second = service.create(fanout, queue, "otra");

		assertThat(second.created()).isFalse();
		assertThat(service.list(fanout, queue)).containsExactly(new Binding(fanout, queue, ""));
	}

	@Test
	void aRepeatedBindingIsReportedAsExistingWithoutDuplicatingIt() {
		String queue = queue("repetido");
		String topic = exchange("temas", ExchangeType.TOPIC);
		service.create(topic, queue, "a.b");

		Creation<Binding> second = service.create(topic, queue, "a.b");

		assertThat(second.created()).isFalse();
		assertThat(service.list(topic, queue)).hasSize(1);
	}

	@Test
	void aQueueCanHaveSeveralRoutingKeysAndTheyCanBeDeletedIndividually() {
		String queue = queue("varias");
		String direct = exchange("directo", ExchangeType.DIRECT);
		service.create(direct, queue, "alta");
		service.create(direct, queue, "baja");
		service.create(direct, queue, "");

		service.delete(direct, queue, "baja");

		assertThat(service.list(direct, queue)).containsExactlyInAnyOrder(new Binding(direct, queue, "alta"),
				new Binding(direct, queue, ""));
	}

	@Test
	void deletesTheBindingWithAnEmptyRoutingKeyAndTheOneWithAPattern() {
		String queue = queue("borrar");
		String direct = exchange("directo", ExchangeType.DIRECT);
		String topic = exchange("temas", ExchangeType.TOPIC);
		service.create(direct, queue, "");
		service.create(direct, queue, "otra");
		service.create(topic, queue, "a.#");
		service.create(topic, queue, "a.*");

		service.delete(direct, queue, null);
		service.delete(topic, queue, "a.#");

		assertThat(service.list(null, queue)).containsExactlyInAnyOrder(new Binding(direct, queue, "otra"),
				new Binding(topic, queue, "a.*"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "a.#", "a.*", "a b", "a+b", "a~b", "~", "a%b", "a/b", "ñ€", "a?b&c=d", "a-b_c.d", "a#" })
	void bindingsWhoseKeyHasSpecialCharactersCanBeCreatedAndDeleted(String key) {
		String queue = queue("especial");
		String direct = exchange("directo", ExchangeType.DIRECT);
		service.create(direct, queue, key);
		assertThat(service.list(direct, queue)).containsExactly(new Binding(direct, queue, key));

		service.delete(direct, queue, key);

		assertThat(service.list(direct, queue)).isEmpty();
	}

	@Test
	void deletingAMissingBindingIsNotFoundAndKeepsTheOthers() {
		String queue = queue("inexistente");
		String direct = exchange("directo", ExchangeType.DIRECT);
		service.create(direct, queue, "alta");

		assertThatThrownBy(() -> service.delete(direct, queue, "otra")).isInstanceOf(NotFoundException.class)
			.hasMessage("El binding indicado no existe.");

		assertThat(service.list(direct, queue)).hasSize(1);
	}

	@Test
	void theDefaultExchangeIsRejectedAndItsImplicitBindingsAreNotListed() {
		String queue = queue("defecto");

		assertThatThrownBy(() -> service.create("", queue, queue)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.delete("", queue, queue)).isInstanceOf(ValidationException.class);

		assertThat(service.list(null, queue)).isEmpty();
		assertThat(service.list("", null)).isEmpty();
	}

	@Test
	void theExchangeAndTheQueueMustExist() {
		String queue = queue("existe");
		String direct = exchange("directo", ExchangeType.DIRECT);

		assertThatThrownBy(() -> service.create("no-existe-" + UUID.randomUUID(), queue, "a"))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.create(direct, "no-existe-" + UUID.randomUUID(), "a"))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void invalidTopicPatternsAreRejectedWithoutCreatingAnything() {
		String queue = queue("patron");
		String topic = exchange("temas", ExchangeType.TOPIC);

		assertThatThrownBy(() -> service.create(topic, queue, "a..b")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.create(topic, queue, "a".repeat(256))).isInstanceOf(ValidationException.class);

		assertThat(service.list(topic, queue)).isEmpty();
	}

	@Test
	void deletingAnExchangeOrAQueueRemovesItsBindings() {
		String queue = queue("cascada");
		String direct = exchange("directo", ExchangeType.DIRECT);
		String other = exchange("otro", ExchangeType.DIRECT);
		service.create(direct, queue, "a");
		service.create(other, queue, "b");

		exchanges.delete(direct);

		assertThat(service.list(null, queue)).containsExactly(new Binding(other, queue, "b"));
		queues.delete(queue);
		assertThat(service.list(other, null)).isEmpty();
	}

	@Test
	void theBindingReallyRoutesMessages() throws Exception {
		String queue = queue("enruta");
		String topic = exchange("temas", ExchangeType.TOPIC);
		service.create(topic, queue, "a.*");

		String routed = publish(topic, "a.b");
		String notRouted = publish(topic, "a.b.c");

		assertThat(routed).contains("\"routed\":true");
		assertThat(notRouted).contains("\"routed\":false");
	}

	@Test
	void namesWithSpecialCharactersWorkInBindings() {
		String queue = "q/%ñ " + UUID.randomUUID();
		String exchange = "e/%ñ " + UUID.randomUUID();
		queues.create(queue, null);
		createdQueues.add(queue);
		exchanges.create(exchange, ExchangeType.DIRECT);
		createdExchanges.add(exchange);

		service.create(exchange, queue, "k");

		assertThat(service.list(exchange, queue)).containsExactly(new Binding(exchange, queue, "k"));
		service.delete(exchange, queue, "k");
		assertThat(service.list(exchange, queue)).isEmpty();
	}

	private String publish(String exchange, String routingKey) throws Exception {
		return RabbitMqContainerSupport.managementPost("/api/exchanges/%2F/" + encode(exchange) + "/publish",
				"{\"properties\":{},\"routing_key\":\"" + routingKey + "\",\"payload\":\"x\",\"payload_encoding\":\"string\"}")
			.body();
	}

	private String exchange(String base, ExchangeType type) {
		String name = "it-" + base + "-" + UUID.randomUUID();
		exchanges.create(name, type);
		createdExchanges.add(name);
		return name;
	}

	private String queue(String base) {
		String name = "it-" + base + "-" + UUID.randomUUID();
		queues.create(name, null);
		createdQueues.add(name);
		return name;
	}

	private static void ignoreMissing(Runnable deletion) {
		try {
			deletion.run();
		}
		catch (NotFoundException alreadyDeleted) {
			// la propia prueba ya lo eliminó
		}
	}

	private static String encode(String name) {
		return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
	}

}
