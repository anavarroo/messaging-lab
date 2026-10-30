package com.messaginglab.generator.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.generator.Destination;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.support.InMemoryBrokerTopology;
import com.messaginglab.support.RecordingBrokerPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RabbitMqMessagePublisherTest {

	private int topologyReads;

	private final InMemoryBrokerTopology topology = new InMemoryBrokerTopology() {
		@Override
		public java.util.List<com.messaginglab.model.Exchange> listExchanges() {
			topologyReads++;
			return super.listExchanges();
		}

		@Override
		public java.util.List<com.messaginglab.model.Queue> listQueues() {
			topologyReads++;
			return super.listQueues();
		}
	};

	private final RecordingBrokerPublisher broker = new RecordingBrokerPublisher();

	private final RabbitMqMessagePublisher publisher = new RabbitMqMessagePublisher(broker, topology);

	@BeforeEach
	void setUp() {
		topology.createExchange("pizzeria", ExchangeType.TOPIC);
		topology.createQueue("cocina", null);
	}

	@Test
	void publishesToAnExchangeWithItsRoutingKey() {
		boolean routed = publisher.publish(RabbitMqDestination.toExchange("pizzeria", "pedido.pizza"), "{\"a\": 1}");

		assertThat(routed).isTrue();
		assertThat(broker.published()).containsExactly(new PublishRequest("pizzeria", "pedido.pizza", "{\"a\": 1}"));
	}

	@Test
	void publishesToAQueueThroughTheDefaultExchangeUsingItsNameAsRoutingKey() {
		publisher.publish(RabbitMqDestination.toQueue("cocina"), "x");

		assertThat(broker.published()).containsExactly(new PublishRequest("", "cocina", "x"));
	}

	@Test
	void reportsAMessageThatReachedNoQueue() {
		broker.routed(false);

		assertThat(publisher.publish(RabbitMqDestination.toExchange("pizzeria", "nadie"), "x")).isFalse();
	}

	@Test
	void anUnroutedMessageToAQueueMeansTheQueueNoLongerExists() {
		broker.routed(false);

		assertThatThrownBy(() -> publisher.publish(RabbitMqDestination.toQueue("cocina"), "x")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «cocina» ya no existe.");
	}

	@Test
	void verifiesThatTheExchangeOrTheQueueExists() {
		publisher.verify(RabbitMqDestination.toExchange("pizzeria", "a"));
		publisher.verify(RabbitMqDestination.toExchange("amq.topic", "a"));
		publisher.verify(RabbitMqDestination.toQueue("cocina"));

		assertThatThrownBy(() -> publisher.verify(RabbitMqDestination.toExchange("fantasma", "a"))).isInstanceOf(NotFoundException.class)
			.hasMessage("El exchange «fantasma» no existe.");
		assertThatThrownBy(() -> publisher.verify(RabbitMqDestination.toQueue("fantasma"))).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «fantasma» no existe.");
	}

	@Test
	void doesNotCheckTheTopologyOnEveryPublication() {
		topologyReads = 0;

		for (int i = 0; i < 100; i++) {
			publisher.publish(RabbitMqDestination.toExchange("pizzeria", "a"), "x");
			publisher.publish(RabbitMqDestination.toQueue("cocina"), "x");
		}

		assertThat(broker.published()).hasSize(200);
		assertThat(topologyReads).isZero();
	}

	@Test
	void onlyAcceptsRabbitMqDestinations() {
		Destination other = () -> "otro";

		assertThatThrownBy(() -> publisher.publish(other, "x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> publisher.verify(other)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatesTheDestinationWhenBuildingIt() {
		assertThatThrownBy(() -> RabbitMqDestination.toExchange(null, "a")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> RabbitMqDestination.toExchange("", "a")).isInstanceOf(ValidationException.class)
			.hasMessage("Indica el exchange de destino.");
		assertThatThrownBy(() -> RabbitMqDestination.toExchange("pizzeria", "a".repeat(256))).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> RabbitMqDestination.toQueue("")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> RabbitMqDestination.toQueue(null)).isInstanceOf(ValidationException.class);
		assertThat(RabbitMqDestination.toExchange("pizzeria", null).routingKey()).isEmpty();
	}

	@Test
	void describesTheDestinationInSpanish() {
		assertThat(RabbitMqDestination.toExchange("pizzeria", "pedido.pizza").describe())
			.isEqualTo("el exchange «pizzeria» con la routing key «pedido.pizza»");
		assertThat(RabbitMqDestination.toExchange("avisos", "").describe()).isEqualTo("el exchange «avisos»");
		assertThat(RabbitMqDestination.toQueue("cocina").describe()).isEqualTo("la cola «cocina»");
	}

}
