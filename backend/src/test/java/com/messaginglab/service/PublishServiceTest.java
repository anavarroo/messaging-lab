package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;
import com.messaginglab.support.InMemoryBrokerTopology;
import com.messaginglab.support.RecordingBrokerPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublishServiceTest {

	private final InMemoryBrokerTopology topology = new InMemoryBrokerTopology();

	private final RecordingBrokerPublisher publisher = new RecordingBrokerPublisher();

	private final PublishService service = new PublishService(topology, publisher);

	@BeforeEach
	void setUp() {
		topology.createExchange("temas", ExchangeType.TOPIC);
	}

	@Test
	void publishesToAnExistingExchangeAndReturnsTheRoutingResult() {
		PublishResult result = service.publish("temas", "a.b", "hola");

		assertThat(result.routed()).isTrue();
		assertThat(publisher.published()).containsExactly(new PublishRequest("temas", "a.b", "hola"));
	}

	@Test
	void reportsWhenTheMessageCouldNotBeRouted() {
		publisher.routed(false);

		assertThat(service.publish("temas", "a.b", "hola").routed()).isFalse();
	}

	@Test
	void aMissingRoutingKeyIsPublishedAsEmpty() {
		service.publish("temas", null, "hola");

		assertThat(publisher.published()).containsExactly(new PublishRequest("temas", "", "hola"));
	}

	@Test
	void rejectsAnEmptyOrMissingBodyWithoutPublishing() {
		for (String body : new String[] { null, "" }) {
			assertThatThrownBy(() -> service.publish("temas", "a", body)).isInstanceOf(ValidationException.class)
				.hasMessage("El cuerpo del mensaje no puede estar vacío.");
		}
		assertThat(publisher.published()).isEmpty();
	}

	@Test
	void acceptsABodyOfExactly64Kb() {
		service.publish("temas", "a", "x".repeat(64 * 1024));

		assertThat(publisher.published()).hasSize(1);
	}

	@Test
	void rejectsABodyOf64KbPlusOneByte() {
		assertThatThrownBy(() -> service.publish("temas", "a", "x".repeat(64 * 1024 + 1)))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("64 KB");

		assertThat(publisher.published()).isEmpty();
	}

	@Test
	void theLimitCountsBytesInUtf8NotCharacters() {
		// 32768 caracteres de 2 bytes ocupan exactamente 64 KB; uno más los supera
		service.publish("temas", "a", "ñ".repeat(32 * 1024));
		assertThatThrownBy(() -> service.publish("temas", "a", "ñ".repeat(32 * 1024 + 1)))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.publish("temas", "a", "€".repeat(21846))).isInstanceOf(ValidationException.class);

		assertThat(publisher.published()).hasSize(1);
	}

	@Test
	void keepsSpecialCharactersAndMarkupAsLiteralText() {
		String body = "<script>alert('x')</script> & \"ñ\" 😀\n";

		service.publish("temas", "a", body);

		assertThat(publisher.published().get(0).body()).isEqualTo(body);
	}

	@Test
	void aMissingExchangeIsNotFoundAndNothingIsPublished() {
		assertThatThrownBy(() -> service.publish("fantasma", "a", "hola")).isInstanceOf(NotFoundException.class)
			.hasMessage("El exchange «fantasma» no existe.");

		assertThat(publisher.published()).isEmpty();
	}

	@Test
	void aMissingExchangeNameIsRejected() {
		assertThatThrownBy(() -> service.publish(null, "a", "hola")).isInstanceOf(ValidationException.class);
	}

	@Test
	void rejectsARoutingKeyOfMoreThan255Characters() {
		assertThatThrownBy(() -> service.publish("temas", "a".repeat(256), "hola"))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("La routing key");

		assertThat(publisher.published()).isEmpty();
	}

	@Test
	void publishesToTheDefaultExchangeUsingAQueueNameAsRoutingKey() {
		topology.createQueue("cola1", null);

		service.publish("", "cola1", "directo a la cola");

		assertThat(publisher.published()).containsExactly(new PublishRequest("", "cola1", "directo a la cola"));
	}

	@Test
	void publishingToTheDefaultExchangeWithAMissingQueueIsLeftToTheBrokerAsNotRouted() {
		publisher.routed(false);

		assertThat(service.publish("", "no-existe", "hola").routed()).isFalse();
	}

	@Test
	void thePredefinedExchangesAcceptPublications() {
		service.publish("amq.topic", "a", "hola");

		assertThat(publisher.published()).hasSize(1);
	}

}
