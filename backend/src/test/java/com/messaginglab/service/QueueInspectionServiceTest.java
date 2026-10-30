package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.support.InMemoryBrokerTopology;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueInspectionServiceTest {

	private final InMemoryBrokerTopology broker = new InMemoryBrokerTopology();

	private final QueueInspectionService service = new QueueInspectionService(broker);

	@BeforeEach
	void setUp() {
		broker.createQueue("cola", null);
	}

	@Test
	void anEmptyQueueHasNoMessagesAndIsNotTruncated() {
		QueueInspection inspection = service.inspect("cola");

		assertThat(inspection.messages()).isEmpty();
		assertThat(inspection.total()).isZero();
		assertThat(inspection.truncated()).isFalse();
	}

	@Test
	void showsTheMessagesInQueueOrder() {
		addMessages(3);

		QueueInspection inspection = service.inspect("cola");

		assertThat(inspection.messages()).extracting(QueueMessage::body).containsExactly("m0", "m1", "m2");
		assertThat(inspection.total()).isEqualTo(3);
		assertThat(inspection.truncated()).isFalse();
	}

	@Test
	void exactly50MessagesAreShownWithoutTruncation() {
		addMessages(50);

		QueueInspection inspection = service.inspect("cola");

		assertThat(inspection.messages()).hasSize(50);
		assertThat(inspection.total()).isEqualTo(50);
		assertThat(inspection.truncated()).isFalse();
	}

	@Test
	void with51MessagesOnlyTheFirst50AreShownAndItIsTruncatedWithTheTotal() {
		addMessages(51);

		QueueInspection inspection = service.inspect("cola");

		assertThat(inspection.messages()).hasSize(50);
		assertThat(inspection.messages().get(0).body()).isEqualTo("m0");
		assertThat(inspection.messages().get(49).body()).isEqualTo("m49");
		assertThat(inspection.total()).isEqualTo(51);
		assertThat(inspection.truncated()).isTrue();
	}

	@Test
	void keepsTheDeathInfoOfADeadLetterQueueMessage() {
		DeathInfo death = new DeathInfo("origen", "rejected");
		broker.addReadyMessages("cola", new QueueMessage("x", "ex", "rk", true, death));

		assertThat(service.inspect("cola").messages().get(0).deathInfo()).isEqualTo(death);
	}

	@Test
	void inspectingDoesNotRemoveTheMessages() {
		addMessages(3);

		service.inspect("cola");
		service.inspect("cola");

		assertThat(broker.listQueues()).containsExactly(new Queue("cola", null, 3, 0, 3));
	}

	@Test
	void inspectingAMissingQueueIsNotFound() {
		assertThatThrownBy(() -> service.inspect("fantasma")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «fantasma» no existe.");
	}

	@Test
	void aMissingQueueNameIsRejected() {
		assertThatThrownBy(() -> service.inspect(null)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.inspect("")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.purge(null)).isInstanceOf(ValidationException.class);
	}

	@Test
	void purgingRemovesTheReadyMessagesAndKeepsTheQueueAndItsBindings() {
		broker.createExchange("ex", ExchangeType.DIRECT);
		broker.createBinding("ex", "cola", "k");
		addMessages(5);

		service.purge("cola");

		assertThat(service.inspect("cola").messages()).isEmpty();
		assertThat(broker.listQueues()).extracting(Queue::name).containsExactly("cola");
		assertThat(broker.listBindings("ex", "cola")).hasSize(1);
	}

	@Test
	void purgingDoesNotTouchTheMessagesPendingConfirmation() {
		addMessages(5);
		broker.setUnacknowledged("cola", 2);

		service.purge("cola");

		assertThat(broker.listQueues()).containsExactly(new Queue("cola", null, 0, 2, 2));
	}

	@Test
	void purgingAMissingQueueIsNotFound() {
		assertThatThrownBy(() -> service.purge("fantasma")).isInstanceOf(NotFoundException.class);
	}

	private void addMessages(int count) {
		broker.addReadyMessages("cola", IntStream.range(0, count)
			.mapToObj(i -> new QueueMessage("m" + i, "", "cola", false, null))
			.toArray(QueueMessage[]::new));
	}

}
