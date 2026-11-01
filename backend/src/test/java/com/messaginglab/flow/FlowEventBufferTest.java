package com.messaginglab.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FlowEventBufferTest {

	private static FlowEvent event(long id) {
		return FlowTestData.publish("router:x", "k").withId(id);
	}

	@Test
	void returnsTheMostRecentFirstAndRespectsTheLimit() {
		FlowEventBuffer buffer = new FlowEventBuffer(10);
		IntStream.rangeClosed(1, 5).forEach(id -> buffer.add(event(id)));

		assertThat(buffer.recent(3)).extracting(FlowEvent::id).containsExactly(5L, 4L, 3L);
		assertThat(buffer.recent(100)).hasSize(5);
	}

	@Test
	void keepsOnlyTheLast1000AndDropsTheOldest() {
		FlowEventBuffer buffer = new FlowEventBuffer(FlowService.BUFFER_SIZE);
		IntStream.rangeClosed(1, 1_500).forEach(id -> buffer.add(event(id)));

		assertThat(buffer.recent(5_000)).hasSize(1_000);
		assertThat(buffer.recent(1)).extracting(FlowEvent::id).containsExactly(1_500L);
		assertThat(buffer.recent(5_000).getLast().id()).isEqualTo(501L);
	}

	@Test
	void isEmptyAtFirst() {
		assertThat(new FlowEventBuffer(5).recent(10)).isEmpty();
	}

	@Test
	void supportsConcurrentWritersWithoutLosingTheCapacityBound() throws Exception {
		FlowEventBuffer buffer = new FlowEventBuffer(100);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch done = new CountDownLatch(8);
		for (int t = 0; t < 8; t++) {
			pool.execute(() -> {
				IntStream.range(0, 1_000).forEach(i -> {
					buffer.add(event(i));
					buffer.recent(10);
				});
				done.countDown();
			});
		}
		done.await();
		pool.shutdown();

		assertThat(buffer.recent(1_000)).hasSize(100);
	}

	@Test
	void markAnUnroutedPublicationAndNothingElse() {
		assertThat(FlowTestData.publish("router:x", "k").unrouted()).isTrue();
		assertThat(FlowTestData.publish("router:x", "k", "destination:q").unrouted()).isFalse();
		assertThat(FlowTestData.deliver("destination:q", "consumer:c").unrouted()).isFalse();
	}

}
