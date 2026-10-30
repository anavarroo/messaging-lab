package com.messaginglab.generator;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** {@link Scheduler} real, sobre un grupo de hilos. */
public final class ThreadPoolScheduler implements Scheduler {

	private final ScheduledThreadPoolExecutor executor;

	public ThreadPoolScheduler(int threads) {
		this.executor = new ScheduledThreadPoolExecutor(threads, task -> {
			Thread thread = new Thread(task, "generator");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.setRemoveOnCancelPolicy(true);
	}

	@Override
	public Handle schedule(Runnable task, long delayMs) {
		ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
		return () -> future.cancel(false);
	}

	@Override
	public void shutdown() {
		executor.shutdownNow();
	}

}
