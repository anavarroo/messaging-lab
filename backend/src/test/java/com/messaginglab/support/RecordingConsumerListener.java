package com.messaginglab.support;

import com.messaginglab.gateway.BrokerConsumers.ConsumerListener;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.StopReason;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Receptor de consumidor para pruebas: guarda lo recibido y permite esperarlo. */
public class RecordingConsumerListener implements ConsumerListener {

	private final BlockingQueue<DeliveredMessage> messages = new LinkedBlockingQueue<>();

	private final List<StopReason> stops = new CopyOnWriteArrayList<>();

	@Override
	public void onMessage(DeliveredMessage message) {
		messages.add(message);
	}

	@Override
	public void onStopped(StopReason reason) {
		stops.add(reason);
	}

	/** Espera el siguiente mensaje; falla la prueba si no llega a tiempo. */
	public DeliveredMessage next() throws InterruptedException {
		DeliveredMessage message = messages.poll(10, TimeUnit.SECONDS);
		if (message == null) {
			throw new AssertionError("No ha llegado ningún mensaje en 10 s");
		}
		return message;
	}

	/** Devuelve el siguiente mensaje si llega en el plazo, o {@code null}. */
	public DeliveredMessage nextWithin(long millis) throws InterruptedException {
		return messages.poll(millis, TimeUnit.MILLISECONDS);
	}

	public List<StopReason> stops() {
		return stops;
	}

}
