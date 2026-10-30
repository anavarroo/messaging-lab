package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ActionOutcome;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.StopReason;
import com.messaginglab.support.FakeBrokerConsumers;
import com.messaginglab.support.FakeBrokerConsumers.FakeConsumer;
import com.messaginglab.support.InMemoryBrokerTopology;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsumerSessionServiceTest {

	private final InMemoryBrokerTopology topology = new InMemoryBrokerTopology();

	private final FakeBrokerConsumers broker = new FakeBrokerConsumers();

	private final ConsumerSessionService service = new ConsumerSessionService(topology, broker);

	private final Events events = new Events();

	private String session;

	@BeforeEach
	void setUp() {
		topology.createQueue("cola", null);
		session = service.openSession(events);
	}

	// ---- sesiones y ciclo de vida (T43)

	@Test
	void startsAConsumerAndAnnouncesIt() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);

		assertThat(consumer.name()).isEqualTo("c1");
		assertThat(consumer.queue()).isEqualTo("cola");
		assertThat(consumer.ackMode()).isEqualTo(AckMode.MANUAL);
		assertThat(consumer.id()).isNotBlank();
		assertThat(events.log).containsExactly("started:c1");
		assertThat(broker.started()).hasSize(1);
		assertThat(broker.last().ackMode).isEqualTo(AckMode.MANUAL);
	}

	@Test
	void sessionsHaveDistinctIds() {
		assertThat(service.openSession(new Events())).isNotEqualTo(session);
	}

	@Test
	void aRepeatedNameInTheSameSessionIsAConflict() {
		service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);

		assertThatThrownBy(() -> service.startConsumer(session, "c1", "cola", AckMode.MANUAL))
			.isInstanceOf(ConflictException.class)
			.hasMessage("Ya hay un consumidor con el nombre «c1» en esta sesión.");

		assertThat(broker.started()).hasSize(1);
	}

	@Test
	void theSameNameIsAllowedInAnotherSession() {
		service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);

		service.startConsumer(service.openSession(new Events()), "c1", "cola", AckMode.AUTOMATIC);

		assertThat(broker.started()).hasSize(2);
	}

	@Test
	void severalConsumersCanShareAQueue() {
		service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		service.startConsumer(session, "c2", "cola", AckMode.MANUAL);

		assertThat(broker.started()).extracting(consumer -> consumer.queue).containsExactly("cola", "cola");
	}

	@Test
	void aConsumerNameCanBeReusedAfterItStops() {
		ConsumerInfo first = service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);
		service.stopConsumer(session, first.id());

		service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);

		assertThat(broker.started()).hasSize(2);
	}

	@Test
	void rejectsInvalidInputWithoutStartingAnything() {
		assertThatThrownBy(() -> service.startConsumer(session, null, "cola", AckMode.MANUAL))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.startConsumer(session, "  ", "cola", AckMode.MANUAL))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.startConsumer(session, "a".repeat(256), "cola", AckMode.MANUAL))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.startConsumer(session, "a\nb", "cola", AckMode.MANUAL))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.startConsumer(session, "c1", null, AckMode.MANUAL))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.startConsumer(session, "c1", "cola", null))
			.isInstanceOf(ValidationException.class);

		assertThat(broker.started()).isEmpty();
	}

	@Test
	void aQueueThatDoesNotExistIsNotFound() {
		assertThatThrownBy(() -> service.startConsumer(session, "c1", "fantasma", AckMode.MANUAL))
			.isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «fantasma» no existe.");

		assertThat(broker.started()).isEmpty();
	}

	@Test
	void aMissingSessionIsNotFound() {
		assertThatThrownBy(() -> service.startConsumer("no-existe", "c1", "cola", AckMode.MANUAL))
			.isInstanceOf(NotFoundException.class)
			.hasMessage("La sesión no existe o ya ha terminado.");
		assertThatThrownBy(() -> service.stopConsumer("no-existe", "x")).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.resolve("no-existe", "x", 1, MessageAction.ACK))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void ifTheBrokerFailsToStartTheConsumerItIsNotRegisteredAndTheNameStaysFree() {
		broker.failStartWith(new BrokerUnavailableException("RabbitMQ no está disponible."));
		assertThatThrownBy(() -> service.startConsumer(session, "c1", "cola", AckMode.MANUAL))
			.isInstanceOf(BrokerUnavailableException.class);
		broker.failStartWith(null);

		service.startConsumer(session, "c1", "cola", AckMode.MANUAL);

		assertThat(events.log).containsExactly("started:c1");
	}

	@Test
	void stoppingAConsumerStopsItInTheBrokerAndAnnouncesIt() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);

		service.stopConsumer(session, consumer.id());

		assertThat(broker.last().stopped()).isTrue();
		assertThat(events.log).containsExactly("started:c1", "stopped:c1:USER");
		assertThatThrownBy(() -> service.stopConsumer(session, consumer.id())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void stoppingAMissingConsumerIsNotFound() {
		assertThatThrownBy(() -> service.stopConsumer(session, "fantasma")).isInstanceOf(NotFoundException.class);
	}

	@Test
	void closingTheSessionStopsAllItsConsumersAndOnlyThoseOfThatSession() {
		Events otherEvents = new Events();
		String other = service.openSession(otherEvents);
		service.startConsumer(session, "a", "cola", AckMode.MANUAL);
		service.startConsumer(session, "b", "cola", AckMode.AUTOMATIC);
		service.startConsumer(other, "c", "cola", AckMode.MANUAL);

		service.closeSession(session);

		assertThat(broker.started().get(0).stopped()).isTrue();
		assertThat(broker.started().get(1).stopped()).isTrue();
		assertThat(broker.started().get(2).stopped()).isFalse();
		assertThatThrownBy(() -> service.startConsumer(session, "d", "cola", AckMode.MANUAL))
			.isInstanceOf(NotFoundException.class);
		service.startConsumer(other, "d", "cola", AckMode.MANUAL);
	}

	@Test
	void closingTheSessionDoesNotSendEventsToTheClosedStream() {
		service.startConsumer(session, "a", "cola", AckMode.MANUAL);

		service.closeSession(session);
		broker.last().deliver("tarde");

		assertThat(events.log).containsExactly("started:a");
	}

	@Test
	void closingASessionTwiceOrOneThatDoesNotExistIsHarmless() {
		service.closeSession(session);
		service.closeSession(session);
		service.closeSession("no-existe");
	}

	// ---- entrega y fin por causa ajena

	@Test
	void forwardsTheReceivedMessagesToTheSession() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);

		broker.last().deliver("hola");

		assertThat(events.log).containsExactly("started:c1", "message:" + consumer.id() + ":1:hola");
	}

	@Test
	void messagesThatArriveWhileStartingAreSentAfterTheConsumerIsAnnounced() {
		FakeBrokerConsumers eager = new FakeBrokerConsumers() {
			@Override
			public ConsumerHandle start(String queue, AckMode ackMode, ConsumerListener listener) {
				ConsumerHandle handle = super.start(queue, ackMode, listener);
				last().deliver("antes de anunciar");
				return handle;
			}
		};
		ConsumerSessionService eagerService = new ConsumerSessionService(topology, eager);
		Events eagerEvents = new Events();
		String eagerSession = eagerService.openSession(eagerEvents);

		ConsumerInfo consumer = eagerService.startConsumer(eagerSession, "c1", "cola", AckMode.MANUAL);

		assertThat(eagerEvents.log).containsExactly("started:c1",
				"message:" + consumer.id() + ":1:antes de anunciar");
	}

	@Test
	void whenTheQueueIsDeletedTheConsumerEndsWithAMessageInSpanish() {
		service.startConsumer(session, "c1", "cola", AckMode.MANUAL);

		broker.last().brokerStops(StopReason.QUEUE_DELETED);

		assertThat(events.log).containsExactly("started:c1", "stopped:c1:QUEUE_DELETED");
		assertThat(events.lastStopMessage).isEqualTo("La cola «cola» se ha eliminado: el consumidor «c1» ha terminado.");
	}

	@Test
	void whenTheConnectionIsLostTheConsumerEndsAndTellsThePendingOnesGoBack() {
		service.startConsumer(session, "c1", "cola", AckMode.MANUAL);

		broker.last().brokerStops(StopReason.CONNECTION_LOST);

		assertThat(events.log).containsExactly("started:c1", "stopped:c1:CONNECTION_LOST");
		assertThat(events.lastStopMessage).contains("Se ha perdido la comunicación con RabbitMQ")
			.contains("volverán a la cola");
	}

	@Test
	void aConsumerThatEndedCannotBeStoppedOrResolvedAgain() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");
		broker.last().brokerStops(StopReason.QUEUE_DELETED);

		assertThatThrownBy(() -> service.stopConsumer(session, consumer.id())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, MessageAction.ACK))
			.isInstanceOf(NotFoundException.class);
	}

	// ---- resolución de mensajes (T44)

	@Test
	void confirmsAMessage() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");

		ActionOutcome outcome = service.resolve(session, consumer.id(), tag, MessageAction.ACK);

		assertThat(outcome).isEqualTo(ActionOutcome.CONFIRMED);
		assertThat(broker.last().resolutions()).containsExactly(tag + ":ACK");
	}

	@Test
	void requeuesAMessage() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");

		assertThat(service.resolve(session, consumer.id(), tag, MessageAction.REQUEUE))
			.isEqualTo(ActionOutcome.REQUEUED);
		assertThat(broker.last().resolutions()).containsExactly(tag + ":REQUEUE");
	}

	@Test
	void rejectingWithADeadLetterQueueReportsDeadLettered() {
		topology.createQueue("fallidos", null);
		topology.createQueue("conDlq", "fallidos");
		ConsumerInfo consumer = service.startConsumer(session, "c1", "conDlq", AckMode.MANUAL);
		long tag = broker.last().deliver("x");

		assertThat(service.resolve(session, consumer.id(), tag, MessageAction.REJECT))
			.isEqualTo(ActionOutcome.DEAD_LETTERED);
		assertThat(broker.last().resolutions()).containsExactly(tag + ":REJECT");
	}

	@Test
	void rejectingWithoutADeadLetterQueueReportsDiscarded() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");

		assertThat(service.resolve(session, consumer.id(), tag, MessageAction.REJECT))
			.isEqualTo(ActionOutcome.DISCARDED);
	}

	@Test
	void rejectingWhenTheDeadLetterQueueWasDeletedReportsDiscarded() {
		topology.createQueue("fallidos", null);
		topology.createQueue("conDlq", "fallidos");
		ConsumerInfo consumer = service.startConsumer(session, "c1", "conDlq", AckMode.MANUAL);
		long tag = broker.last().deliver("x");
		topology.deleteQueue("fallidos");

		assertThat(service.resolve(session, consumer.id(), tag, MessageAction.REJECT))
			.isEqualTo(ActionOutcome.DISCARDED);
	}

	@Test
	void aMessageCanOnlyBeResolvedOnce() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");
		service.resolve(session, consumer.id(), tag, MessageAction.ACK);

		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, MessageAction.ACK))
			.isInstanceOf(ConflictException.class)
			.hasMessage("Este mensaje ya se ha resuelto.");
		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, MessageAction.REJECT))
			.isInstanceOf(ConflictException.class);

		assertThat(broker.last().resolutions()).hasSize(1);
	}

	@Test
	void aMessageThatWasNeverDeliveredIsNotFound() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		broker.last().deliver("x");

		assertThatThrownBy(() -> service.resolve(session, consumer.id(), 99, MessageAction.ACK))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.resolve(session, consumer.id(), 0, MessageAction.ACK))
			.isInstanceOf(NotFoundException.class);
		assertThat(broker.last().resolutions()).isEmpty();
	}

	@Test
	void aMissingConsumerIsNotFound() {
		assertThatThrownBy(() -> service.resolve(session, "fantasma", 1, MessageAction.ACK))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void aMissingActionIsRejected() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");

		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, null))
			.isInstanceOf(ValidationException.class);
	}

	@Test
	void anAutomaticConsumerHasNothingToResolve() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.AUTOMATIC);
		long tag = broker.last().deliver("x");

		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, MessageAction.ACK))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("confirma automáticamente");
	}

	@Test
	void eachManualConsumerResolvesItsOwnMessages() {
		ConsumerInfo first = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		FakeConsumer firstConsumer = broker.last();
		ConsumerInfo second = service.startConsumer(session, "c2", "cola", AckMode.MANUAL);
		FakeConsumer secondConsumer = broker.last();
		long firstTag = firstConsumer.deliver("para c1");
		long secondTag = secondConsumer.deliver("para c2");

		service.resolve(session, second.id(), secondTag, MessageAction.ACK);
		service.resolve(session, first.id(), firstTag, MessageAction.REQUEUE);

		assertThat(firstConsumer.resolutions()).containsExactly(firstTag + ":REQUEUE");
		assertThat(secondConsumer.resolutions()).containsExactly(secondTag + ":ACK");
	}

	@Test
	void theNextMessageCanBeResolvedAfterTheCurrentOne() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long first = broker.last().deliver("uno");
		service.resolve(session, consumer.id(), first, MessageAction.ACK);
		long second = broker.last().deliver("dos");

		assertThat(service.resolve(session, consumer.id(), second, MessageAction.ACK))
			.isEqualTo(ActionOutcome.CONFIRMED);
		assertThatThrownBy(() -> service.resolve(session, consumer.id(), first, MessageAction.ACK))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void ifTheBrokerFailsToApplyTheActionTheMessageStaysPendingAndCanBeRetried() {
		ConsumerInfo consumer = service.startConsumer(session, "c1", "cola", AckMode.MANUAL);
		long tag = broker.last().deliver("x");
		broker.last().failResolveWith(new BrokerUnavailableException("RabbitMQ no está disponible."));

		assertThatThrownBy(() -> service.resolve(session, consumer.id(), tag, MessageAction.ACK))
			.isInstanceOf(BrokerUnavailableException.class);
		broker.last().failResolveWith(null);

		assertThat(service.resolve(session, consumer.id(), tag, MessageAction.ACK)).isEqualTo(ActionOutcome.CONFIRMED);
	}

	/** Receptor de la sesión: guarda un registro legible de los eventos recibidos. */
	private static final class Events implements ConsumerSessionService.SessionListener {

		private final List<String> log = new ArrayList<>();

		private final java.util.Map<String, String> names = new java.util.HashMap<>();

		private String lastStopMessage;

		@Override
		public void consumerStarted(ConsumerInfo consumer) {
			names.put(consumer.id(), consumer.name());
			log.add("started:" + consumer.name());
		}

		@Override
		public void messageReceived(String consumerId, DeliveredMessage message) {
			log.add("message:" + consumerId + ":" + message.deliveryTag() + ":" + message.message().body());
		}

		@Override
		public void consumerStopped(String consumerId, StopReason reason, String message) {
			log.add("stopped:" + names.get(consumerId) + ":" + reason);
			lastStopMessage = message;
		}

	}

}
