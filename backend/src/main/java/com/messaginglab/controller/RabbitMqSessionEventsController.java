package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqSessionEventsApi;
import com.messaginglab.service.ConsumerSessionService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Abre el flujo de eventos de una sesión. El flujo da vida a los consumidores de la sesión: cuando termina,
 * falla o expira, la sesión se cierra y se detienen sus consumidores (los mensajes pendientes de confirmar
 * vuelven a la cola).
 */
@RestController
public class RabbitMqSessionEventsController implements RabbitMqSessionEventsApi {

	private final ConsumerSessionService sessions;

	private final Duration heartbeatInterval;

	private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "sse-heartbeat");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * @param heartbeatInterval cada cuánto se envía el latido. El navegador que se va no avisa: el servidor solo
	 * se entera de que el flujo está roto cuando falla un envío (normalmente al segundo latido), así que este
	 * intervalo es también el tiempo que tardan en volver a la cola los mensajes pendientes al cerrar la pestaña
	 */
	@Autowired
	public RabbitMqSessionEventsController(ConsumerSessionService sessions,
			@Value("${messaginglab.session.heartbeat-interval:15s}") Duration heartbeatInterval) {
		this.sessions = sessions;
		this.heartbeatInterval = heartbeatInterval;
	}

	@Override
	public ResponseEntity<SseEmitter> openSessionEvents() {
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(open(new SseEmitter(0L)));
	}

	/** Conecta una sesión nueva con el emisor. Separado para poder probarlo con un emisor falso. */
	SseEmitter open(SseEmitter emitter) {
		AtomicBoolean closed = new AtomicBoolean();
		ScheduledFuture<?>[] heartbeat = new ScheduledFuture<?>[1];
		String[] sessionId = new String[1];
		Runnable close = () -> {
			if (closed.compareAndSet(false, true)) {
				if (heartbeat[0] != null) {
					heartbeat[0].cancel(false);
				}
				if (sessionId[0] != null) {
					sessions.closeSession(sessionId[0]);
				}
			}
		};
		SessionEventStream stream = new SessionEventStream(emitter, () -> {
			close.run();
		});
		sessionId[0] = sessions.openSession(stream);
		emitter.onCompletion(close);
		emitter.onTimeout(close);
		emitter.onError(error -> close.run());
		heartbeat[0] = heartbeats.scheduleWithFixedDelay(stream::heartbeat, heartbeatInterval.toMillis(),
				heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
		stream.sessionStarted(sessionId[0]);
		return emitter;
	}

	@PreDestroy
	void shutdown() {
		heartbeats.shutdownNow();
	}

}
