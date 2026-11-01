package com.messaginglab.flow;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Reparte a las pantallas abiertas lo que cuenta una {@link FlowSource}. Solo escucha a la fuente mientras haya
 * al menos una pantalla suscrita. Quien lo use debe llamar a {@link #tick()} una vez por segundo.
 */
public final class FlowService {

	public static final int MAX_EVENTS_PER_SECOND = 50;

	public static final int BUFFER_SIZE = 1_000;

	public static final int TICKS_KEPT = 120;

	private static final int SUBSCRIBER_QUEUE = 1_000;

	private final FlowSource source;

	private final FlowTopologyProvider topologyProvider;

	private final Clock clock;

	private final FlowEventBuffer buffer = new FlowEventBuffer(BUFFER_SIZE);

	private final EventSampler sampler = new EventSampler(MAX_EVENTS_PER_SECOND);

	private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

	private final Deque<FlowTick> ticks = new ArrayDeque<>();

	private final AtomicLong ids = new AtomicLong();

	private volatile Map<String, LongAdder> published = new ConcurrentHashMap<>();

	private volatile Map<String, LongAdder> delivered = new ConcurrentHashMap<>();

	private long lastTickMs;

	private boolean listening;

	private FlowSourceStatus lastStatus;

	private FlowTopology lastTopology = FlowTopology.EMPTY;

	public FlowService(FlowSource source, FlowTopologyProvider topologyProvider, Clock clock) {
		this.source = source;
		this.topologyProvider = topologyProvider;
		this.clock = clock;
	}

	public synchronized Subscription subscribe() {
		Subscription subscription = new Subscription();
		if (subscriptions.isEmpty()) {
			lastTickMs = clock.millis();
		}
		syncSource(true);
		subscriptions.add(subscription);
		lastTopology = topologyProvider.topology();
		subscription.offer(new FlowMessage.Status(lastStatus));
		subscription.offer(new FlowMessage.Topology(lastTopology));
		ticks.forEach(tick -> subscription.offer(new FlowMessage.Tick(tick)));
		return subscription;
	}

	/** Eventos recientes, el más reciente primero. */
	public List<FlowEvent> recentEvents(int limit) {
		return buffer.recent(limit);
	}

	/** Eventos que no se enviaron en el último segundo por superar el máximo; 0 si no hay pantallas abiertas. */
	public synchronized long omittedLastSecond() {
		return ticks.isEmpty() ? 0 : ticks.getLast().omitted();
	}

	public FlowSourceStatus status() {
		return source.status();
	}

	public FlowTopology topology() {
		return topologyProvider.topology();
	}

	/** Cierra el segundo: publica los ritmos y los listos, y avisa de los cambios de estado y de topología. */
	public synchronized void tick() {
		if (subscriptions.isEmpty()) {
			return;
		}
		syncSource(true);
		long now = clock.millis();
		double seconds = Math.max(now - lastTickMs, 1) / 1000.0;
		lastTickMs = now;
		Map<String, LongAdder> publishedCounts = published;
		Map<String, LongAdder> deliveredCounts = delivered;
		published = new ConcurrentHashMap<>();
		delivered = new ConcurrentHashMap<>();
		FlowTick tick = new FlowTick(clock.instant(), perSecond(publishedCounts, seconds),
				perSecond(deliveredCounts, seconds), topologyProvider.ready(), sampler.takeOmitted());
		ticks.addLast(tick);
		if (ticks.size() > TICKS_KEPT) {
			ticks.removeFirst();
		}
		broadcast(new FlowMessage.Tick(tick));
		FlowTopology topology = topologyProvider.topology();
		if (!topology.equals(lastTopology)) {
			lastTopology = topology;
			broadcast(new FlowMessage.Topology(topology));
		}
	}

	private void ingest(FlowEvent raw) {
		FlowEvent event = raw.withId(ids.incrementAndGet());
		if (event.type() == FlowEvent.Type.PUBLISH) {
			count(published, event.producer());
			count(published, event.router());
			event.destinations().forEach(destination -> count(published, destination));
		}
		else {
			event.destinations().forEach(destination -> count(delivered, destination));
			count(delivered, event.consumer());
		}
		buffer.add(event);
		if (sampler.admit(clock.millis())) {
			broadcast(new FlowMessage.Event(event));
		}
	}

	private static void count(Map<String, LongAdder> counts, String node) {
		if (node != null) {
			counts.computeIfAbsent(node, key -> new LongAdder()).increment();
		}
	}

	private static Map<String, Double> perSecond(Map<String, LongAdder> counts, double seconds) {
		Map<String, Double> rates = new ConcurrentHashMap<>();
		counts.forEach((node, count) -> rates.put(node, count.sum() / seconds));
		return rates;
	}

	/** Escucha a la fuente solo cuando hay pantallas y está activa; avisa cuando cambia su estado. */
	private void syncSource(boolean hasScreens) {
		FlowSourceStatus status = source.status();
		boolean shouldListen = hasScreens && status.state() == FlowSourceStatus.State.ACTIVE;
		if (shouldListen && !listening) {
			source.start(this::ingest);
		}
		else if (!shouldListen && listening) {
			source.stop();
		}
		listening = shouldListen;
		if (!status.equals(lastStatus)) {
			lastStatus = status;
			broadcast(new FlowMessage.Status(status));
		}
	}

	private void broadcast(FlowMessage message) {
		subscriptions.forEach(subscription -> subscription.offer(message));
	}

	private synchronized void unsubscribe(Subscription subscription) {
		subscriptions.remove(subscription);
		if (subscriptions.isEmpty()) {
			syncSource(false);
			lastStatus = null;
			ticks.clear();
		}
	}

	/** Mensajes pendientes de una pantalla. Si no los lee a tiempo, se descartan los más antiguos. */
	public final class Subscription implements AutoCloseable {

		private final BlockingQueue<FlowMessage> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE);

		void offer(FlowMessage message) {
			while (!queue.offer(message)) {
				queue.poll();
			}
		}

		/** Espera el siguiente mensaje; {@code null} si pasa el tiempo sin que llegue ninguno. */
		public FlowMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
			return queue.poll(timeout, unit);
		}

		public List<FlowMessage> pending() {
			List<FlowMessage> result = new ArrayList<>();
			queue.drainTo(result);
			return result;
		}

		@Override
		public void close() {
			unsubscribe(this);
		}

	}

}
