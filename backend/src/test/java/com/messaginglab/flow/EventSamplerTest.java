package com.messaginglab.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventSamplerTest {

	@Test
	void lets50PassPerSecondAndCountsTheRest() {
		EventSampler sampler = new EventSampler(50);
		int admitted = 0;
		for (int i = 0; i < 500; i++) {
			if (sampler.admit(1_000 + i)) {
				admitted++;
			}
		}

		assertThat(admitted).isEqualTo(50);
		assertThat(sampler.takeOmitted()).isEqualTo(450);
		assertThat(sampler.takeOmitted()).isZero();
	}

	@Test
	void startsANewWindowEachSecond() {
		EventSampler sampler = new EventSampler(2);
		assertThat(sampler.admit(0)).isTrue();
		assertThat(sampler.admit(10)).isTrue();
		assertThat(sampler.admit(20)).isFalse();

		assertThat(sampler.admit(1_000)).isTrue();
		assertThat(sampler.admit(1_001)).isTrue();
		assertThat(sampler.admit(1_002)).isFalse();
		assertThat(sampler.takeOmitted()).isEqualTo(2);
	}

	@Test
	void doesNotOmitAnythingBelowTheLimit() {
		EventSampler sampler = new EventSampler(50);
		for (int i = 0; i < 40; i++) {
			assertThat(sampler.admit(i * 10L)).isTrue();
		}

		assertThat(sampler.takeOmitted()).isZero();
	}

}
