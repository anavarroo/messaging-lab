package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.Creation;
import com.messaginglab.model.Queue;
import com.messaginglab.support.InMemoryBrokerTopology;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private final QueueService service = new QueueService(broker);

	@Test
	void createsAQueueWithoutDeadLetterQueue() {
		Creation<Queue> creation = service.create("pedidos", null);

		assertThat(creation.created()).isTrue();
		assertThat(creation.value()).isEqualTo(new Queue("pedidos", null, 0, 0, 0));
		assertThat(service.list()).containsExactly(new Queue("pedidos", null, 0, 0, 0));
	}

	@Test
	void createsAQueueWithAnExistingDeadLetterQueue() {
		service.create("fallidos", null);

		Creation<Queue> creation = service.create("pedidos", "fallidos");

		assertThat(creation.created()).isTrue();
		assertThat(creation.value().deadLetterQueue()).isEqualTo("fallidos");
		assertThat(service.list()).extracting(Queue::name, Queue::deadLetterQueue)
			.containsExactly(tuple("fallidos", null),
					tuple("pedidos", "fallidos"));
	}

	@Test
	void rejectsADeadLetterQueueThatDoesNotExistAndDoesNotCreateTheQueue() {
		assertThatThrownBy(() -> service.create("pedidos", "fantasma")).isInstanceOf(ValidationException.class)
			.hasMessage("La dead-letter queue «fantasma» no existe.");

		assertThat(service.list()).isEmpty();
	}

	@Test
	void rejectsAQueueAsItsOwnDeadLetterQueue() {
		service.create("pedidos", null);

		assertThatThrownBy(() -> service.create("pedidos", "pedidos")).isInstanceOf(ValidationException.class)
			.hasMessage("Una cola no puede ser su propia dead-letter queue.");
		assertThatThrownBy(() -> service.create("nueva", "nueva")).isInstanceOf(ValidationException.class)
			.hasMessage("Una cola no puede ser su propia dead-letter queue.");
	}

	@Test
	void creatingAnIdenticalQueueDoesNotDuplicateIt() {
		service.create("fallidos", null);
		service.create("pedidos", "fallidos");

		Creation<Queue> second = service.create("pedidos", "fallidos");

		assertThat(second.created()).isFalse();
		assertThat(service.list().stream().filter(queue -> queue.name().equals("pedidos"))).hasSize(1);
	}

	@Test
	void creatingAQueueWithAnotherConfigurationIsAConflictAndKeepsTheExistingOne() {
		service.create("fallidos", null);
		service.create("pedidos", null);

		assertThatThrownBy(() -> service.create("pedidos", "fallidos")).isInstanceOf(ConflictException.class)
			.hasMessageContaining("dead-letter queue");

		assertThat(service.list()).contains(new Queue("pedidos", null, 0, 0, 0));
	}

	@Test
	void rejectsInvalidNamesWithoutTouchingTheBroker() {
		for (String name : new String[] { null, "", "   ", "a".repeat(256), "amq.mia", "a\nb" }) {
			assertThatThrownBy(() -> service.create(name, null)).isInstanceOf(ValidationException.class);
		}
		assertThat(service.list()).isEmpty();
	}

	@Test
	void theNameValidationMessageTalksAboutTheQueue() {
		assertThatThrownBy(() -> service.create("", null)).hasMessage("El nombre de la cola no puede estar vacío.");
	}

	@Test
	void acceptsNamesWithSpecialCharacters() {
		service.create("a/b%c ñ", null);

		assertThat(service.list()).extracting(Queue::name).containsExactly("a/b%c ñ");
	}

	@Test
	void listsTheQueuesByName() {
		service.create("b", null);
		service.create("a", null);

		assertThat(service.list()).extracting(Queue::name).containsExactly("a", "b");
	}

	@Test
	void deletesAQueue() {
		service.create("pedidos", null);

		service.delete("pedidos");

		assertThat(service.list()).isEmpty();
	}

	@Test
	void deletingAMissingQueueIsNotFound() {
		assertThatThrownBy(() -> service.delete("pedidos")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «pedidos» no existe.");
	}

	@Test
	void deletingWithoutANameIsRejected() {
		assertThatThrownBy(() -> service.delete(null)).isInstanceOf(ValidationException.class);
	}

	@Test
	void aQueueThatIsTheDeadLetterQueueOfOthersCanBeDeletedAndTheOthersRemain() {
		service.create("fallidos", null);
		service.create("pedidos", "fallidos");

		service.delete("fallidos");

		assertThat(service.list()).extracting(Queue::name).containsExactly("pedidos");
	}

}
