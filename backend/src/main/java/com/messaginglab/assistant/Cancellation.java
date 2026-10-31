package com.messaginglab.assistant;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Permite pedir que una respuesta en curso se detenga (el usuario la cancela o se cierra la conexión). */
public final class Cancellation {

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private volatile boolean cancelled;

	public void cancel() {
		cancelled = true;
		listeners.forEach(Runnable::run);
	}

	/**
	 * Avisa cuando se cancele, para poder soltar algo que esté bloqueado esperando (por ejemplo, la lectura de la
	 * respuesta del modelo). Si ya estaba cancelada, avisa al momento.
	 */
	public void onCancel(Runnable listener) {
		listeners.add(listener);
		if (cancelled) {
			listener.run();
		}
	}

	public boolean isCancelled() {
		return cancelled;
	}

}
