package com.messaginglab.generator;

/**
 * Ejecuta tareas pasado un tiempo. Es una interfaz propia para poder sustituir el paso del tiempo en las pruebas.
 */
public interface Scheduler {

	/** Programa la tarea para dentro de {@code delayMs} milisegundos. */
	Handle schedule(Runnable task, long delayMs);

	/** Detiene el planificador y descarta lo pendiente. */
	void shutdown();

	/** Tarea programada que todavía se puede cancelar. */
	interface Handle {

		void cancel();

	}

}
