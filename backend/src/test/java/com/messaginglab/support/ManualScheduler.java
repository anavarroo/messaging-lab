package com.messaginglab.support;

import com.messaginglab.generator.Scheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Planificador en el que el tiempo solo pasa cuando la prueba lo dice. Comparte el reloj con el servicio que se
 * prueba, para que lo que ve ese servicio coincida con el momento en que se ejecuta cada tarea.
 */
public class ManualScheduler implements Scheduler {

	private final MutableClock clock;

	private final List<Task> tasks = new ArrayList<>();

	private long sequence;

	private boolean shutdown;

	public ManualScheduler(MutableClock clock) {
		this.clock = clock;
	}

	@Override
	public Handle schedule(Runnable task, long delayMs) {
		Task scheduled = new Task(clock.millis() + delayMs, sequence++, task);
		tasks.add(scheduled);
		return () -> scheduled.cancelled = true;
	}

	@Override
	public void shutdown() {
		shutdown = true;
		tasks.clear();
	}

	public boolean isShutdown() {
		return shutdown;
	}

	/** Tareas pendientes no canceladas. */
	public int pending() {
		return (int) tasks.stream().filter(task -> !task.cancelled).count();
	}

	/** Deja pasar el tiempo, ejecutando en orden las tareas que vencen (y las que ellas programen). */
	public void advance(Duration duration) {
		long target = clock.millis() + duration.toMillis();
		while (true) {
			Task next = tasks.stream()
				.filter(task -> !task.cancelled && task.due <= target)
				.min(Comparator.comparingLong((Task task) -> task.due).thenComparingLong(task -> task.sequence))
				.orElse(null);
			if (next == null) {
				break;
			}
			tasks.remove(next);
			if (next.due > clock.millis()) {
				clock.advance(Duration.ofMillis(next.due - clock.millis()));
			}
			next.runnable.run();
		}
		if (target > clock.millis()) {
			clock.advance(Duration.ofMillis(target - clock.millis()));
		}
	}

	private static final class Task {

		private final long due;

		private final long sequence;

		private final Runnable runnable;

		private boolean cancelled;

		private Task(long due, long sequence, Runnable runnable) {
			this.due = due;
			this.sequence = sequence;
			this.runnable = runnable;
		}

	}

}
