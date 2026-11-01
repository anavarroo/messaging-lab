package com.messaginglab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.exception.ValidationException;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IntervalTest {

	@Test
	void aFixedIntervalAlwaysWaitsTheSame() {
		Interval interval = Interval.fixed(500);

		assertThat(IntStream.range(0, 50).mapToObj(i -> interval.next(new Random(i))).collect(Collectors.toSet()))
			.containsExactly(500L);
	}

	@Test
	void aRangeWaitsRandomlyInsideItIncludingBothEnds() {
		Interval interval = Interval.range(3, 5);
		Random random = new Random(1);
		Set<Long> values = IntStream.range(0, 500).mapToObj(i -> interval.next(random)).collect(Collectors.toSet());

		assertThat(values).containsExactlyInAnyOrder(3L, 4L, 5L);
	}

	@Test
	void theWorstCaseRateIsTheShortestWait() {
		assertThat(Interval.fixed(100).maxPerSecond()).isEqualTo(10.0);
		assertThat(Interval.range(200, 800).maxPerSecond()).isEqualTo(5.0);
		assertThat(Interval.fixed(2).maxPerSecond()).isEqualTo(500.0);
	}

	@Test
	void theLimitsOfTheAcceptedRangeAreInclusive() {
		assertThat(Interval.fixed(Interval.MIN_MS)).isNotNull();
		assertThat(Interval.fixed(Interval.MAX_MS)).isNotNull();
		assertThat(Interval.range(Interval.MIN_MS, Interval.MAX_MS)).isNotNull();
	}

	@Test
	void tooShortOrTooLongIsRejected() {
		assertThatThrownBy(() -> Interval.fixed(1)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("entre 2 ms");
		assertThatThrownBy(() -> Interval.fixed(0)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> Interval.fixed(-5)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> Interval.fixed(Interval.MAX_MS + 1)).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> Interval.range(1, 10)).isInstanceOf(ValidationException.class);
	}

	@Test
	void aMinimumGreaterThanTheMaximumIsRejected() {
		assertThatThrownBy(() -> Interval.range(800, 200)).isInstanceOf(ValidationException.class)
			.hasMessageContaining("no puede ser mayor");
	}

	@Test
	void buildsFromOptionalValuesExplainingWhatIsMissing() {
		assertThat(Interval.of(Interval.Mode.FIXED, 500L, null, null, "la frecuencia")).isEqualTo(Interval.fixed(500));
		assertThat(Interval.of(Interval.Mode.RANGE, null, 200L, 800L, "la frecuencia")).isEqualTo(Interval.range(200, 800));
		assertThatThrownBy(() -> Interval.of(null, null, null, null, "la frecuencia")).hasMessageContaining("fija o está dentro de un rango");
		assertThatThrownBy(() -> Interval.of(Interval.Mode.FIXED, null, 1L, 2L, "la frecuencia")).hasMessageContaining("valor fijo");
		assertThatThrownBy(() -> Interval.of(Interval.Mode.RANGE, 5L, null, 2L, "la frecuencia")).hasMessageContaining("mínimo y el máximo");
	}

}
