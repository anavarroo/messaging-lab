package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TopicPatternValidatorTest {

	@ParameterizedTest
	@ValueSource(strings = { "", "a", "a.b.c", "*", "#", "a.*", "a.#", "*.b.*", "#.error", "a.*.c.#", "orders.eu-west_1" })
	void acceptsValidPatterns(String pattern) {
		assertThatCode(() -> TopicPatternValidator.validate(pattern)).doesNotThrowAnyException();
	}

	@Test
	void acceptsNull() {
		assertThatCode(() -> TopicPatternValidator.validate(null)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "a..b", ".a", "a.", ".", "..", "a.b..c" })
	void rejectsEmptyWords(String pattern) {
		assertThatThrownBy(() -> TopicPatternValidator.validate(pattern))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("palabras vacías");
	}

	@ParameterizedTest
	@ValueSource(strings = { "a*", "*b", "a.b*", "a.#b", "**", "##", "a.*#" })
	void rejectsWildcardsMixedWithOtherCharacters(String pattern) {
		assertThatThrownBy(() -> TopicPatternValidator.validate(pattern))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("deben ser una palabra completa");
	}

	@Test
	void theMessageNamesTheOffendingWord() {
		assertThatThrownBy(() -> TopicPatternValidator.validate("a.b*.c"))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("«b*»");
	}

	@Test
	void appliesTheLengthAndControlCharacterRules() {
		assertThatCode(() -> TopicPatternValidator.validate("a".repeat(255))).doesNotThrowAnyException();
		assertThatThrownBy(() -> TopicPatternValidator.validate("a".repeat(256)))
			.isInstanceOf(ValidationException.class)
			.hasMessage("El patrón no puede superar los 255 caracteres.");
		assertThatThrownBy(() -> TopicPatternValidator.validate("a.\nb"))
			.isInstanceOf(ValidationException.class)
			.hasMessage("El patrón no puede contener caracteres de control.");
	}

}
