package com.messaginglab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ValidationException;
import com.messaginglab.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadTemplateTest {

	private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

	private final MutableClock clock = new MutableClock(START);

	private PayloadGenerator generator(String template) {
		return PayloadTemplate.parse(template).generator(new Random(42), clock);
	}

	private List<String> generate(String template, int count) {
		PayloadGenerator generator = generator(template);
		return IntStream.range(0, count).mapToObj(i -> generator.next()).toList();
	}

	// ---- texto fijo

	@Test
	void aTemplateWithoutValuesIsTheSameTextEveryTime() {
		assertThat(generate("{\"a\": 1}", 3)).containsOnly("{\"a\": 1}");
	}

	@Test
	void keepsSingleBracesAndTextAroundTheValues() {
		assertThat(generator("{\"x\": {{contador}}, \"y\": \"fin\"}").next()).isEqualTo("{\"x\": 1, \"y\": \"fin\"}");
	}

	@Test
	void allowsSpacesInsideTheBraces() {
		assertThat(generator("{{ contador }}").next()).isEqualTo("1");
		assertThat(generator("{{ numero( 5 , 5 ) }}").next()).isEqualTo("5");
	}

	// ---- numero

	@Test
	void numeroWithWholeLimitsGivesWholeNumbersInsideTheRangeIncludingBothEnds() {
		List<String> values = generate("{{numero(1,3)}}", 500);

		assertThat(values).allMatch(value -> value.matches("[123]"));
		assertThat(new HashSet<>(values)).containsExactlyInAnyOrder("1", "2", "3");
	}

	@Test
	void numeroWithDecimalLimitsGivesTwoDecimalsInsideTheRange() {
		generate("{{numero(20.5,30.5)}}", 500).forEach(value -> {
			assertThat(value).matches("\\d+\\.\\d{2}");
			assertThat(Double.parseDouble(value)).isBetween(20.5, 30.5);
		});
	}

	@Test
	void numeroAcceptsAnExplicitNumberOfDecimalsAndNegativeLimits() {
		generate("{{numero(-1,1,4)}}", 200).forEach(value -> {
			assertThat(value).matches("-?\\d\\.\\d{4}");
			assertThat(Double.parseDouble(value)).isBetween(-1.0, 1.0);
		});
		assertThat(generator("{{numero(0,10,0)}}").next()).matches("\\d+");
	}

	@Test
	void numeroNeverPrintsMinusZero() {
		assertThat(generator("{{numero(-0.001,-0.0001,2)}}").next()).isEqualTo("0.00");
	}

	// ---- deriva

	@Test
	void derivaMovesLittleByLittleAndStaysInsideItsLimits() {
		List<String> values = generate("{{deriva(22,18,26,0.3)}}", 2000);
		double previous = 22;

		for (String value : values) {
			double current = Double.parseDouble(value);
			assertThat(current).isBetween(18.0, 26.0);
			assertThat(Math.abs(current - previous)).isLessThanOrEqualTo(0.31);
			previous = current;
		}
		assertThat(new HashSet<>(values).size()).isGreaterThan(20);
	}

	@Test
	void derivaClampsAtTheLimit() {
		generate("{{deriva(26,18,26,5)}}", 500).forEach(value -> assertThat(Double.parseDouble(value)).isBetween(18.0, 26.0));
	}

	@Test
	void eachGeneratorHasItsOwnDeriveState() {
		PayloadTemplate template = PayloadTemplate.parse("{{deriva(50,0,100,10)}}");
		PayloadGenerator first = template.generator(new Random(1), clock);
		PayloadGenerator second = template.generator(new Random(1), clock);

		assertThat(first.next()).isEqualTo(second.next());
		first.next();
		first.next();
		assertThat(second.next()).isNotEqualTo(first.next());
	}

	// ---- senoidal

	@Test
	void senoidalFollowsASineWaveOverTime() {
		PayloadGenerator generator = generator("{{senoidal(0,100,10)}}");

		assertThat(generator.next()).isEqualTo("50.00");
		clock.advance(Duration.ofMillis(2500));
		assertThat(generator.next()).isEqualTo("100.00");
		clock.advance(Duration.ofMillis(2500));
		assertThat(generator.next()).isEqualTo("50.00");
		clock.advance(Duration.ofMillis(2500));
		assertThat(generator.next()).isEqualTo("0.00");
		clock.advance(Duration.ofMillis(2500));
		assertThat(generator.next()).isEqualTo("50.00");
	}

	@Test
	void senoidalAcceptsDecimals() {
		clock.advance(Duration.ZERO);
		assertThat(generator("{{senoidal(0,1,4,3)}}").next()).isEqualTo("0.500");
	}

	// ---- contador, ahora, elegir, booleano

	@Test
	void contadorCountsFromOneOrFromTheGivenNumber() {
		assertThat(generate("{{contador}}", 3)).containsExactly("1", "2", "3");
		assertThat(generate("{{contador(100)}}", 3)).containsExactly("100", "101", "102");
	}

	@Test
	void eachContadorInATemplateCountsOnItsOwn() {
		assertThat(generate("{{contador}}-{{contador(10)}}", 2)).containsExactly("1-10", "2-11");
	}

	@Test
	void ahoraGivesTheCurrentTimeInIsoFormat() {
		PayloadGenerator generator = generator("\"{{ahora}}\"");

		assertThat(generator.next()).isEqualTo("\"2026-01-01T10:00:00Z\"");
		clock.advance(Duration.ofSeconds(90));
		assertThat(generator.next()).isEqualTo("\"2026-01-01T10:01:30Z\"");
	}

	@Test
	void elegirPicksFromTheListAndEventuallyUsesAllOfThem() {
		Set<String> values = new HashSet<>(generate("{{elegir(a,b,c)}}", 300));

		assertThat(values).containsExactlyInAnyOrder("a", "b", "c");
	}

	@Test
	void elegirTrimsTheValuesAndAllowsOneValue() {
		assertThat(generator("{{elegir( solo )}}").next()).isEqualTo("solo");
	}

	@Test
	void repeatingAValueInElegirMakesItMoreLikely() {
		List<String> values = generate("{{elegir(NORMAL,NORMAL,NORMAL,REDUCIDO)}}", 2000);

		long normal = values.stream().filter("NORMAL"::equals).count();
		assertThat(normal).isBetween(1300L, 1700L);
	}

	@Test
	void booleanoGivesTrueOrFalseAtTheGivenProbability() {
		List<String> half = generate("{{booleano}}", 2000);
		List<String> rare = generate("{{booleano(0.02)}}", 2000);

		assertThat(new HashSet<>(half)).containsExactlyInAnyOrder("true", "false");
		assertThat(half.stream().filter("true"::equals).count()).isBetween(850L, 1150L);
		assertThat(rare.stream().filter("true"::equals).count()).isBetween(10L, 90L);
		assertThat(generate("{{booleano(0)}}", 50)).containsOnly("false");
		assertThat(generate("{{booleano(1)}}", 50)).containsOnly("true");
	}

	// ---- ejes

	@Test
	void ejesGivesAnArrayWithTheRequestedNumberOfAxesInsideTheLimits() {
		generate("{{ejes(6,-3.14,3.14)}}", 300).forEach(value -> {
			assertThat(value).startsWith("[").endsWith("]");
			String[] axes = value.substring(1, value.length() - 1).split(", ");
			assertThat(axes).hasSize(6);
			for (String axis : axes) {
				assertThat(axis).matches("-?\\d\\.\\d{4}");
				assertThat(Double.parseDouble(axis)).isBetween(-3.14, 3.14);
			}
		});
	}

	@Test
	void ejesMoveEachAxisSmoothlyStartingInTheMiddle() {
		PayloadGenerator generator = generator("{{ejes(3,-10,10)}}");
		String first = generator.next();
		String second = generator.next();

		assertThat(first).isNotEqualTo(second);
		double[] a = axes(first);
		double[] b = axes(second);
		for (int i = 0; i < 3; i++) {
			assertThat(Math.abs(a[i])).isLessThanOrEqualTo(0.4001);
			assertThat(Math.abs(b[i] - a[i])).isLessThanOrEqualTo(0.4001);
		}
	}

	private static double[] axes(String value) {
		return java.util.Arrays.stream(value.substring(1, value.length() - 1).split(", "))
			.mapToDouble(Double::parseDouble)
			.toArray();
	}

	@Test
	void ejesAcceptsOneAxisAndTheMaximumOf64() {
		assertThat(generator("{{ejes(1,0,1)}}").next().split(",")).hasSize(1);
		assertThat(generator("{{ejes(64,0,1)}}").next().split(",")).hasSize(64);
	}

	// ---- mezcla

	@Test
	void combinesSeveralValuesOfDifferentKindsIntoAValidJsonBody() {
		String body = generator("""
				{"temp": {{deriva(22,18,26,0.3)}}, "ok": {{booleano}}, "n": {{contador}}, "t": "{{ahora}}", "e": {{ejes(2,0,1)}}}""")
			.next();

		assertThat(body).contains("\"n\": 1").contains("\"t\": \"2026-01-01T10:00:00Z\"");
	}

	// ---- errores

	@Test
	void anEmptyOrMissingTemplateIsRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("")).isInstanceOf(ValidationException.class)
			.hasMessage("La plantilla no puede estar vacía.");
		assertThatThrownBy(() -> PayloadTemplate.parse(null)).isInstanceOf(ValidationException.class);
	}

	@Test
	void anUnknownFunctionIsRejectedListingTheAvailableOnesAndWhereItIs() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{\n  \"a\": {{inventada(1)}}\n}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("línea 2, columna 8")
			.hasMessageContaining("la función «inventada» no existe")
			.hasMessageContaining("numero")
			.hasMessageContaining("ejes");
	}

	@Test
	void anUnclosedBraceIsRejectedWithItsPlace() {
		assertThatThrownBy(() -> PayloadTemplate.parse("hola {{numero(1,2)")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("línea 1, columna 6")
			.hasMessageContaining("falta cerrar «{{» con «}}»");
	}

	@Test
	void anUnclosedParenthesisIsRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(1,2}}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("falta cerrar el paréntesis");
	}

	@Test
	void emptyBracesAreRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("a {{ }} b")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("vacío");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{{numero(1)}}", "{{numero(1,2,3,4)}}", "{{ahora(1)}}", "{{ejes(1,2)}}", "{{deriva(1,2,3)}}",
			"{{contador(1,2)}}", "{{elegir}}" })
	void aWrongNumberOfArgumentsIsRejectedWithTheUsage(String template) {
		assertThatThrownBy(() -> PayloadTemplate.parse(template)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("necesita")
			.hasMessageContaining("Uso:");
	}

	@Test
	void aNonNumericArgumentIsRejectedSayingWhichOne() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(1,abc)}}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("el argumento 2 de «numero» («abc») no es un número");
		assertThatCode(() -> PayloadTemplate.parse("{{numero(1,2,5)}}")).doesNotThrowAnyException();
	}

	@Test
	void aDecimalWrittenWithACommaIsReadAsAnotherArgumentAndRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(1,2,5,7)}}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("necesita");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(1,2.5.3)}}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("no es un número");
	}

	@Test
	void aMinimumGreaterThanTheMaximumIsRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(10,1)}}")).isInstanceOf(ValidationException.class)
			.hasMessageContaining("el mínimo (10) no puede ser mayor que el máximo (1)");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{ejes(3,5,1)}}")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> PayloadTemplate.parse("{{senoidal(5,1,10)}}")).isInstanceOf(ValidationException.class);
	}

	@Test
	void invalidParametersOfTheOtherFunctionsAreRejected() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{deriva(50,0,10,1)}}")).hasMessageContaining("valor inicial");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{deriva(5,0,10,0)}}")).hasMessageContaining("paso");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{senoidal(0,1,0)}}")).hasMessageContaining("periodo");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{booleano(2)}}")).hasMessageContaining("probabilidad");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{ejes(0,0,1)}}")).hasMessageContaining("entero entre 1 y 64");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{ejes(65,0,1)}}")).hasMessageContaining("entero entre 1 y 64");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{ejes(2.5,0,1)}}")).hasMessageContaining("entero");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{numero(0,1,11)}}")).hasMessageContaining("entre 0 y 10");
		assertThatThrownBy(() -> PayloadTemplate.parse("{{elegir(a,,b)}}")).hasMessageContaining("vacío");
	}

	@Test
	void everyErrorIsInSpanishAndNeverShowsTechnicalDetails() {
		for (String bad : List.of("{{x}}", "{{numero(a,b)}}", "{{", "{{numero(1)}}")) {
			assertThatThrownBy(() -> PayloadTemplate.parse(bad)).isInstanceOf(ValidationException.class)
				.hasMessageStartingWith("Error en la plantilla")
				.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("Exception", "java.", "NumberFormat"));
		}
	}

	@Test
	void doesNotExecuteCodeOrExpressions() {
		assertThatThrownBy(() -> PayloadTemplate.parse("{{java.lang.Runtime.getRuntime().exec(\"ls\")}}"))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> PayloadTemplate.parse("{{1+1}}")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> PayloadTemplate.parse("${7*7} {{numero(1,2)+3}}")).isInstanceOf(ValidationException.class);
		assertThat(generator("${7*7} #{1+1} <% 2 %>").next()).isEqualTo("${7*7} #{1+1} <% 2 %>");
	}

	// ---- 64 KB

	@Test
	void aTemplateThatAlwaysProducesMoreThan64KbIsRejectedWhenParsed() {
		assertThatThrownBy(() -> PayloadTemplate.parse("x".repeat(PayloadTemplate.MAX_BYTES + 1)))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("64 KB");
	}

	@Test
	void aBodyOfExactly64KbIsAccepted() {
		PayloadGenerator generator = generator("x".repeat(PayloadTemplate.MAX_BYTES));

		assertThat(generator.next()).hasSize(PayloadTemplate.MAX_BYTES);
	}

	@Test
	void theLimitCountsBytesInUtf8NotCharacters() {
		assertThat(generator("ñ".repeat(PayloadTemplate.MAX_BYTES / 2)).next()).isNotEmpty();
		assertThatThrownBy(() -> PayloadTemplate.parse("ñ".repeat(PayloadTemplate.MAX_BYTES / 2 + 1)))
			.isInstanceOf(ValidationException.class);
	}

	@Test
	void functionNamesAreListed() {
		assertThat(PayloadTemplate.functionNames())
			.containsExactlyInAnyOrder("numero", "deriva", "senoidal", "contador", "ahora", "elegir", "booleano", "ejes");
	}

	@Test
	void parsingAcceptsTheSystemClockAndRandomToo() {
		PayloadGenerator generator = PayloadTemplate.parse("{{ahora}}").generator(new Random(), Clock.systemUTC());

		assertThat(generator.next()).endsWith("Z");
	}

}
