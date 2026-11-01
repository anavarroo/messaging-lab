package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ValidationException;
import org.junit.jupiter.api.Test;

class NameValidatorTest {

	@Test
	void acceptsOrdinaryNames() {
		assertThatCode(() -> NameValidator.validateName("pedidos", "de la cola")).doesNotThrowAnyException();
		assertThatCode(() -> NameValidator.validateName("orders.eu-west_1:v2", "del exchange"))
			.doesNotThrowAnyException();
		assertThatCode(() -> NameValidator.validateName("a/b%c ñ é €", "de la cola")).doesNotThrowAnyException();
	}

	@Test
	void rejectsEmptyOrBlankNames() {
		for (String name : new String[] { null, "", "   " }) {
			assertThatThrownBy(() -> NameValidator.validateName(name, "de la cola"))
				.isInstanceOf(ValidationException.class)
				.hasMessage("El nombre de la cola no puede estar vacío.");
		}
	}

	@Test
	void acceptsExactly255CharactersAndRejects256() {
		assertThatCode(() -> NameValidator.validateName("a".repeat(255), "de la cola")).doesNotThrowAnyException();
		assertThatThrownBy(() -> NameValidator.validateName("a".repeat(256), "de la cola"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("El nombre de la cola no puede superar los 255 caracteres.");
	}

	@Test
	void rejectsMultibyteNamesThatExceed255BytesEvenIfTheyHaveFewerCharacters() {
		String name = "€".repeat(100);

		assertThatThrownBy(() -> NameValidator.validateName(name, "del exchange"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("El nombre del exchange no puede ocupar más de 255 bytes en UTF-8.");
	}

	@Test
	void rejectsControlCharacters() {
		for (String name : new String[] { "a\nb", "a\tb", "a\u0000b", "a\u007fb" }) {
			assertThatThrownBy(() -> NameValidator.validateName(name, "de la cola"))
				.isInstanceOf(ValidationException.class)
				.hasMessage("El nombre de la cola no puede contener caracteres de control.");
		}
	}

	@Test
	void rejectsTheReservedPrefix() {
		assertThatThrownBy(() -> NameValidator.validateName("amq.mio", "del exchange"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("El nombre del exchange no puede empezar por «amq.», un prefijo reservado de RabbitMQ.");
		assertThatCode(() -> NameValidator.validateName("amq", "del exchange")).doesNotThrowAnyException();
		assertThatCode(() -> NameValidator.validateName("mi.amq.x", "del exchange")).doesNotThrowAnyException();
	}

	@Test
	void anEmptyRoutingKeyIsValid() {
		assertThatCode(() -> NameValidator.validateRoutingKey("", "La routing key")).doesNotThrowAnyException();
		assertThatCode(() -> NameValidator.validateRoutingKey(null, "La routing key")).doesNotThrowAnyException();
	}

	@Test
	void routingKeysFollowTheSameSizeAndControlCharacterRules() {
		assertThatCode(() -> NameValidator.validateRoutingKey("a".repeat(255), "La routing key"))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> NameValidator.validateRoutingKey("a".repeat(256), "La routing key"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("La routing key no puede superar los 255 caracteres.");
		assertThatThrownBy(() -> NameValidator.validateRoutingKey("a\nb", "La routing key"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("La routing key no puede contener caracteres de control.");
	}

}
