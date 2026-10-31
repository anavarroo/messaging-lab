package com.messaginglab.flow;

import static com.messaginglab.flow.FlowTestData.deliver;
import static com.messaginglab.flow.FlowTestData.publish;
import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.flow.FlowService.Subscription;
import com.messaginglab.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowServiceTest {

	private static final FlowSourceStatus ACTIVE = new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null);

	private static final FlowSourceStatus DISABLED = new FlowSourceStatus(FlowSourceStatus.State.DISABLED, "trace_on");

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));

	private final FakeSource source = new FakeSource();

	private final FakeTopology topology = new FakeTopology();

	private FlowService service;

	@BeforeEach
	void setUp() {
		service = new FlowService(source, topology, clock);
	}

	private static <T extends FlowMessage> List<T> of(Subscription subscription, Class<T> type) {
		return subscription.pending().stream().filter(type::isInstance).map(type::cast).toList();
	}

	@Test
	void doesNotListenToTheSourceWithoutScreens() {
		service.tick();

		assertThat(source.started).isFalse();
		assertThat(source.starts).isZero();
	}

	@Test
	void startsListeningWithTheFirstScreenAndStopsWithTheLast() {
		Subscription first = service.subscribe();
		Subscription second = service.subscribe();
		assertThat(source.started).isTrue();
		assertThat(source.starts).isEqualTo(1);

		first.close();
		assertThat(source.started).isTrue();
		second.close();
		assertThat(source.started).isFalse();
		assertThat(source.stops).isEqualTo(1);
	}

	@Test
	void doesNotStartAnInactiveSourceAndStartsItWhenItBecomesActive() {
		source.status = DISABLED;
		Subscription screen = service.subscribe();
		assertThat(source.started).isFalse();
		assertThat(of(screen, FlowMessage.Status.class)).extracting(m -> m.status().state())
			.containsExactly(FlowSourceStatus.State.DISABLED);

		source.status = ACTIVE;
		service.tick();

		assertThat(source.started).isTrue();
		assertThat(of(screen, FlowMessage.Status.class)).extracting(m -> m.status().state())
			.containsExactly(FlowSourceStatus.State.ACTIVE);
	}

	@Test
	void stopsListeningWhenTheSourceStopsBeingActive() {
		Subscription screen = service.subscribe();
		source.status = new FlowSourceStatus(FlowSourceStatus.State.UNAVAILABLE, "caído");

		service.tick();

		assertThat(source.started).isFalse();
		assertThat(of(screen, FlowMessage.Status.class)).hasSize(2);
	}

	@Test
	void deliversEventsWithIdsAndKeepsThemInTheHistory() {
		Subscription screen = service.subscribe();
		screen.pending();

		source.emit(publish("router:ex", "a.b", "destination:q"));
		source.emit(deliver("destination:q", "consumer:c"));

		assertThat(of(screen, FlowMessage.Event.class)).extracting(m -> m.event().id()).containsExactly(1L, 2L);
		assertThat(service.recentEvents(10)).extracting(FlowEvent::id).containsExactly(2L, 1L);
	}

	@Test
	void sendsAtMost50EventsPerSecondButCountsAllOfThem() {
		Subscription screen = service.subscribe();
		screen.pending();

		for (int i = 0; i < 500; i++) {
			source.emit(publish("router:ex", "k", "destination:q"));
		}
		clock.advance(Duration.ofSeconds(1));
		service.tick();

		List<FlowMessage> received = screen.pending();
		assertThat(received).filteredOn(FlowMessage.Event.class::isInstance).hasSize(50);
		assertThat(service.recentEvents(1_000)).hasSize(500);
		FlowTick tick = ((FlowMessage.Tick) received.stream().filter(FlowMessage.Tick.class::isInstance).findFirst().get()).tick();
		assertThat(tick.omitted()).isEqualTo(450);
		assertThat(tick.published()).containsEntry("router:ex", 500.0).containsEntry("destination:q", 500.0);
	}

	@Test
	void tickReportsPerSecondRatesReadyCountsAndStartsOverEachSecond() {
		topology.ready = Map.of("destination:q", 7L);
		Subscription screen = service.subscribe();
		screen.pending();

		for (int i = 0; i < 10; i++) {
			source.emit(publish("router:ex", "k", "destination:q"));
		}
		for (int i = 0; i < 4; i++) {
			source.emit(deliver("destination:q", "consumer:c"));
		}
		clock.advance(Duration.ofSeconds(2));
		service.tick();
		clock.advance(Duration.ofSeconds(1));
		service.tick();

		List<FlowMessage.Tick> ticks = of(screen, FlowMessage.Tick.class);
		assertThat(ticks.get(0).tick().published()).containsEntry("router:ex", 5.0).containsEntry("producer:app", 5.0);
		assertThat(ticks.get(0).tick().delivered()).containsEntry("destination:q", 2.0).containsEntry("consumer:c", 2.0);
		assertThat(ticks.get(0).tick().ready()).containsEntry("destination:q", 7L);
		assertThat(ticks.get(1).tick().published()).isEmpty();
		assertThat(ticks.get(1).tick().delivered()).isEmpty();
	}

	@Test
	void aNewScreenReceivesTheCurrentStateAndTheLast2MinutesOfTicks() {
		Subscription early = service.subscribe();
		for (int i = 0; i < 130; i++) {
			clock.advance(Duration.ofSeconds(1));
			service.tick();
		}
		early.pending();

		Subscription late = service.subscribe();

		assertThat(of(late, FlowMessage.Tick.class)).hasSize(FlowService.TICKS_KEPT);
		assertThat(late.pending()).isEmpty();
	}

	@Test
	void announcesTopologyChangesOnlyWhenTheyHappen() {
		topology.value = new FlowTopology(List.of(new FlowNode("router:a", FlowNode.Kind.ROUTER, "a", null)), List.of());
		Subscription screen = service.subscribe();
		assertThat(of(screen, FlowMessage.Topology.class)).hasSize(1);

		service.tick();
		assertThat(of(screen, FlowMessage.Topology.class)).isEmpty();

		topology.value = FlowTopology.EMPTY;
		service.tick();
		assertThat(of(screen, FlowMessage.Topology.class)).hasSize(1);
	}

	@Test
	void aSlowScreenDoesNotAccumulateMemoryAndKeepsTheNewestMessages() {
		Subscription slow = service.subscribe();
		slow.pending();

		for (int i = 0; i < 100; i++) {
			clock.advance(Duration.ofSeconds(1));
			for (int j = 0; j < 50; j++) {
				source.emit(publish("router:ex", "k", "destination:q"));
			}
			service.tick();
		}

		List<FlowMessage> queued = slow.pending();
		assertThat(queued.size()).isLessThanOrEqualTo(1_000);
		assertThat(queued.getLast()).isInstanceOf(FlowMessage.Tick.class);
	}

	@Test
	void forgetsTheTicksWhenTheLastScreenLeaves() {
		Subscription first = service.subscribe();
		clock.advance(Duration.ofSeconds(1));
		service.tick();
		first.close();

		Subscription again = service.subscribe();

		assertThat(of(again, FlowMessage.Tick.class)).isEmpty();
	}

	private static final class FakeSource implements FlowSource {

		FlowSourceStatus status = ACTIVE;

		boolean started;

		int starts;

		int stops;

		private Consumer<FlowEvent> sink;

		@Override
		public FlowSourceStatus status() {
			return status;
		}

		@Override
		public void start(Consumer<FlowEvent> sink) {
			this.sink = sink;
			started = true;
			starts++;
		}

		@Override
		public void stop() {
			started = false;
			stops++;
		}

		void emit(FlowEvent event) {
			sink.accept(event);
		}

	}

	private static final class FakeTopology implements FlowTopologyProvider {

		FlowTopology value = FlowTopology.EMPTY;

		Map<String, Long> ready = Map.of();

		@Override
		public FlowTopology topology() {
			return value;
		}

		@Override
		public Map<String, Long> ready() {
			return ready;
		}

	}

}
