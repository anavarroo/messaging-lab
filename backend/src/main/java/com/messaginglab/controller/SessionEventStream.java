package com.messaginglab.controller;

import com.messaginglab.api.model.ConsumerStartedEvent;
import com.messaginglab.api.model.ConsumerStoppedEvent;
import com.messaginglab.api.model.MessageReceivedEvent;
import com.messaginglab.api.model.SessionStartedEvent;
import com.messaginglab.api.model.StopReason;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.service.ConsumerSessionService.SessionListener;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Flujo de eventos de una sesión hacia el navegador. Los eventos llegan desde varios hilos (los de los
 * consumidores y el del latido), así que se envían de uno en uno y en orden.
 * <p>
 * Si el envío falla, el navegador ya no está: se avisa con {@code onBroken} para cerrar la sesión.
 */
final class SessionEventStream implements SessionListener {

	static final String SESSION_STARTED = "session-started";

	static final String CONSUMER_STARTED = "consumer-started";

	static final String MESSAGE_RECEIVED = "message-received";

	static final String CONSUMER_STOPPED = "consumer-stopped";

	private final SseEmitter emitter;

	private final Runnable onBroken;

	private boolean broken;

	SessionEventStream(SseEmitter emitter, Runnable onBroken) {
		this.emitter = emitter;
		this.onBroken = onBroken;
	}

	void sessionStarted(String sessionId) {
		send(SESSION_STARTED, new SessionStartedEvent(sessionId));
	}

	@Override
	public void consumerStarted(ConsumerInfo consumer) {
		send(CONSUMER_STARTED, new ConsumerStartedEvent(ApiMappers.consumer(consumer)));
	}

	@Override
	public void messageReceived(String consumerId, DeliveredMessage message) {
		send(MESSAGE_RECEIVED, new MessageReceivedEvent(consumerId, message.deliveryTag(),
				ApiMappers.queueMessage(message.message())));
	}

	@Override
	public void consumerStopped(String consumerId, com.messaginglab.model.StopReason reason, String message) {
		send(CONSUMER_STOPPED,
				new ConsumerStoppedEvent(consumerId, StopReason.valueOf(reason.name()), message));
	}

	/** Comentario que no es un evento: mantiene viva la conexión y detecta las que ya están muertas. */
	void heartbeat() {
		synchronized (this) {
			if (broken) {
				return;
			}
			try {
				emitter.send(SseEmitter.event().comment("latido"));
			}
			catch (IOException | RuntimeException exception) {
				fail(exception);
			}
		}
	}

	private synchronized void send(String name, Object data) {
		if (broken) {
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | RuntimeException exception) {
			fail(exception);
		}
	}

	private void fail(Exception exception) {
		broken = true;
		try {
			emitter.completeWithError(exception);
		}
		catch (RuntimeException ignored) {
			// el emisor ya estaba terminado
		}
		onBroken.run();
	}

}
