package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Queue;
import com.messaginglab.service.QueueService;
import com.messaginglab.support.RabbitMqContainerSupport;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Colas contra un RabbitMQ real: servicio y adaptador juntos. */
class QueuesIT {

	private final RabbitMqTopology topology = new RabbitMqTopology(
			new ManagementApiClient(RabbitMqContainerSupport.properties()));

	private final QueueService service = new QueueService(topology);

	private final List<String> created = new ArrayList<>();

	@AfterEach
	void deleteWhatTheTestCreated() {
		for (String name : created) {
			try {
				service.delete(name);
			}
			catch (NotFoundException alreadyDeleted) {
				// la propia prueba ya la eliminó
			}
		}
	}

	@Test
	void createsADurableClassicQueueThatAppearsInTheListWithZeroCounters() throws Exception {
		String name = unique("basica");

		Creation<Queue> creation = service.create(name, null);

		assertThat(creation).isEqualTo(new Creation<>(new Queue(name, null, 0, 0, 0), true));
		assertThat(find(name)).isEqualTo(new Queue(name, null, 0, 0, 0));
		HttpResponse<String> details = RabbitMqContainerSupport.managementGet("/api/queues/%2F/" + encode(name));
		assertThat(details.statusCode()).isEqualTo(200);
		assertThat(details.body()).contains("\"durable\":true").contains("\"type\":\"classic\"");
	}

	@Test
	void theDeadLetterQueueIsReadBack() {
		String dead = unique("fallidos");
		String name = unique("con-dlq");
		service.create(dead, null);

		Creation<Queue> creation = service.create(name, dead);

		assertThat(creation.created()).isTrue();
		assertThat(creation.value().deadLetterQueue()).isEqualTo(dead);
		assertThat(find(name).deadLetterQueue()).isEqualTo(dead);
		assertThat(find(dead).deadLetterQueue()).isNull();
	}

	@Test
	void aDeadLetterQueueThatDoesNotExistIsRejectedAndNothingIsCreated() {
		String name = unique("sin-dlq");

		assertThatThrownBy(() -> service.create(name, "no-existe-" + UUID.randomUUID()))
			.isInstanceOf(ValidationException.class);

		assertThat(service.list().stream().map(Queue::name)).doesNotContain(name);
	}

	@Test
	void aQueueCannotBeItsOwnDeadLetterQueue() {
		String name = unique("circular");
		service.create(name, null);

		assertThatThrownBy(() -> service.create(name, name)).isInstanceOf(ValidationException.class)
			.hasMessage("Una cola no puede ser su propia dead-letter queue.");
	}

	@Test
	void creatingAnIdenticalQueueAgainDoesNotDuplicateIt() {
		String dead = unique("fallidos");
		String name = unique("repetida");
		service.create(dead, null);
		service.create(name, dead);

		Creation<Queue> second = service.create(name, dead);

		assertThat(second.created()).isFalse();
		assertThat(second.value()).isEqualTo(new Queue(name, dead, 0, 0, 0));
		assertThat(service.list().stream().filter(queue -> queue.name().equals(name))).hasSize(1);
	}

	@Test
	void creatingItAgainWithAnotherConfigurationIsAConflictAndKeepsTheOriginal() {
		String dead = unique("fallidos");
		String otherDead = unique("otros");
		String withDead = unique("con");
		String without = unique("sin");
		service.create(dead, null);
		service.create(otherDead, null);
		service.create(withDead, dead);
		service.create(without, null);

		assertThatThrownBy(() -> service.create(withDead, otherDead)).isInstanceOf(ConflictException.class)
			.hasMessage("Ya existe con ese nombre pero con otra configuración (difiere en la dead-letter queue).");
		assertThatThrownBy(() -> service.create(withDead, null)).isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> service.create(without, dead)).isInstanceOf(ConflictException.class);

		assertThat(find(withDead).deadLetterQueue()).isEqualTo(dead);
		assertThat(find(without).deadLetterQueue()).isNull();
	}

	@Test
	void theListShowsTheCurrentCountersOfEachQueue() throws Exception {
		String name = unique("contadores");
		service.create(name, null);

		for (int i = 0; i < 3; i++) {
			publishToDefaultExchange(name, "mensaje " + i);
		}

		assertThat(find(name)).isEqualTo(new Queue(name, null, 3, 0, 3));
	}

	@Test
	void identicalRepeatReportsTheCurrentCounters() throws Exception {
		String name = unique("repetida-con-mensajes");
		service.create(name, null);
		publishToDefaultExchange(name, "uno");

		Creation<Queue> second = service.create(name, null);

		assertThat(second).isEqualTo(new Creation<>(new Queue(name, null, 1, 0, 1), false));
	}

	@Test
	void deletesAQueueAndDeletingItAgainIsNotFound() {
		String name = unique("borrar");
		service.create(name, null);

		service.delete(name);

		assertThat(service.list().stream().map(Queue::name)).doesNotContain(name);
		assertThatThrownBy(() -> service.delete(name)).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «" + name + "» no existe.");
	}

	@Test
	void aQueueThatIsTheDeadLetterQueueOfAnotherCanBeDeletedAndTheOtherRemains() {
		String dead = unique("fallidos");
		String name = unique("origen");
		service.create(dead, null);
		service.create(name, dead);

		service.delete(dead);

		assertThat(service.list().stream().map(Queue::name)).contains(name).doesNotContain(dead);
	}

	@Test
	void purgingRemovesTheReadyMessagesAndKeepsTheQueue() throws Exception {
		String name = unique("vaciar");
		service.create(name, null);
		publishToDefaultExchange(name, "uno");
		publishToDefaultExchange(name, "dos");

		topology.purgeQueue(name);

		assertThat(find(name)).isEqualTo(new Queue(name, null, 0, 0, 0));
	}

	@Test
	void purgingAMissingQueueIsNotFound() {
		assertThatThrownBy(() -> topology.purgeQueue("no-existe-" + UUID.randomUUID()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void namesWithSlashesPercentSignsSpacesAndNonAsciiCharactersWork() throws Exception {
		String name = "a/b%c ñ€ " + UUID.randomUUID();
		String dead = "d/e%f ñ " + UUID.randomUUID();
		service.create(dead, null);
		created.add(dead);

		service.create(name, dead);
		created.add(name);

		assertThat(find(name).deadLetterQueue()).isEqualTo(dead);
		assertThat(RabbitMqContainerSupport.managementGet("/api/queues/%2F/" + encode(name)).statusCode())
			.isEqualTo(200);
		service.delete(name);
		assertThat(service.list().stream().map(Queue::name)).doesNotContain(name);
	}

	private Queue find(String name) {
		return service.list().stream().filter(queue -> queue.name().equals(name)).findFirst().orElseThrow();
	}

	private void publishToDefaultExchange(String queue, String payload) throws Exception {
		HttpResponse<String> response = RabbitMqContainerSupport.managementPost("/api/exchanges/%2F/amq.default/publish",
				"{\"properties\":{},\"routing_key\":\"" + queue + "\",\"payload\":\"" + payload
						+ "\",\"payload_encoding\":\"string\"}");
		assertThat(response.body()).contains("\"routed\":true");
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
