package com.messaginglab.service;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.gateway.BrokerConsumers;
import com.messaginglab.gateway.BrokerConsumers.ConsumerHandle;
import com.messaginglab.gateway.BrokerConsumers.ConsumerListener;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ActionOutcome;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.Queue;
import com.messaginglab.model.StopReason;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Sesiones de consumidores en memoria. Una sesión corresponde a una pestaña del navegador y da vida a los
 * consumidores que inicia: al cerrarse, se detienen y RabbitMQ devuelve a la cola los mensajes pendientes
 * de confirmar. Nada de esto se persiste.
 * <p>
 * Un consumidor manual retiene como máximo un mensaje pendiente (el puerto no entrega el siguiente hasta
 * resolver el actual) y cada mensaje se resuelve una sola vez.
 */
@Service
public class ConsumerSessionService {

	private static final int MAX_NAME_LENGTH = 255;

	private final BrokerTopology topology;

	private final BrokerConsumers brokerConsumers;

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	public ConsumerSessionService(BrokerTopology topology, BrokerConsumers brokerConsumers) {
		this.topology = topology;
		this.brokerConsumers = brokerConsumers;
	}

	/** Lo que ocurre en los consumidores de una sesión, para enviarlo a quien la tiene abierta. */
	public interface SessionListener {

		void consumerStarted(ConsumerInfo consumer);

		void messageReceived(String consumerId, DeliveredMessage message);

		/** Un consumidor ha terminado; {@code message} lo explica al usuario. */
		void consumerStopped(String consumerId, StopReason reason, String message);

	}

	/** Abre una sesión y devuelve su identificador. */
	public String openSession(SessionListener listener) {
		String id = UUID.randomUUID().toString();
		sessions.put(id, new Session(listener));
		return id;
	}

	/** Cierra la sesión y detiene todos sus consumidores. No hace nada si ya no existe. */
	public void closeSession(String sessionId) {
		Session session = sessions.remove(sessionId);
		if (session == null) {
			return;
		}
		List<ConsumerState> toStop;
		synchronized (session) {
			session.closed = true;
			toStop = new ArrayList<>(session.consumers.values());
			session.consumers.clear();
		}
		toStop.forEach(ConsumerState::stopQuietly);
	}

	public ConsumerInfo startConsumer(String sessionId, String name, String queueName, AckMode ackMode) {
		Session session = session(sessionId);
		checkName(name);
		if (queueName == null || queueName.isEmpty()) {
			throw new ValidationException("Indica la cola que quiere consumir el consumidor.");
		}
		if (ackMode == null) {
			throw new ValidationException("Indica el modo de confirmación: automático o manual.");
		}
		Queue queue = topology.listQueues()
			.stream()
			.filter(candidate -> candidate.name().equals(queueName))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("La cola «" + queueName + "» no existe."));
		ConsumerState state = new ConsumerState(session,
				new ConsumerInfo(UUID.randomUUID().toString(), name, queue.name(), ackMode));
		synchronized (session) {
			if (session.closed) {
				throw sessionNotFound();
			}
			if (session.consumers.values().stream().anyMatch(other -> other.info.name().equals(name))) {
				throw new ConflictException("Ya hay un consumidor con el nombre «" + name + "» en esta sesión.");
			}
			session.consumers.put(state.info.id(), state);
		}
		ConsumerHandle handle;
		try {
			handle = brokerConsumers.start(queue.name(), ackMode, state);
		}
		catch (RuntimeException exception) {
			session.consumers.remove(state.info.id());
			throw exception;
		}
		state.announce(handle);
		return state.info;
	}

	public void stopConsumer(String sessionId, String consumerId) {
		Session session = session(sessionId);
		ConsumerState state = session.consumers.remove(consumerId);
		if (state == null) {
			throw consumerNotFound();
		}
		state.stopByUser();
	}

	/**
	 * Aplica la acción del usuario sobre el mensaje pendiente de un consumidor manual.
	 * @return qué ha pasado con el mensaje
	 */
	public ActionOutcome resolve(String sessionId, String consumerId, long deliveryTag, MessageAction action) {
		if (action == null) {
			throw new ValidationException("Indica la acción: confirmar, rechazar y reencolar, o rechazar sin reencolar.");
		}
		ConsumerState state = session(sessionId).consumers.get(consumerId);
		if (state == null) {
			throw consumerNotFound();
		}
		return state.resolve(deliveryTag, action);
	}

	@PreDestroy
	void closeAll() {
		new ArrayList<>(sessions.keySet()).forEach(this::closeSession);
	}

	private Session session(String sessionId) {
		Session session = sessions.get(sessionId);
		if (session == null) {
			throw sessionNotFound();
		}
		return session;
	}

	private static void checkName(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("El nombre del consumidor no puede estar vacío.");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new ValidationException(
					"El nombre del consumidor no puede superar los " + MAX_NAME_LENGTH + " caracteres.");
		}
		if (name.chars().anyMatch(Character::isISOControl)) {
			throw new ValidationException("El nombre del consumidor no puede contener caracteres de control.");
		}
	}

	private static NotFoundException sessionNotFound() {
		return new NotFoundException("La sesión no existe o ya ha terminado.");
	}

	private static NotFoundException consumerNotFound() {
		return new NotFoundException("El consumidor no existe en esta sesión: puede que ya haya terminado.");
	}

	private static final class Session {

		private final SessionListener listener;

		private final Map<String, ConsumerState> consumers = new ConcurrentHashMap<>();

		private boolean closed;

		private Session(SessionListener listener) {
			this.listener = listener;
		}

	}

	/** Estado de un consumidor y receptor de lo que ocurre en él, que llega desde los hilos del cliente AMQP. */
	private final class ConsumerState implements ConsumerListener {

		private final Session session;

		private final ConsumerInfo info;

		/** Eventos que llegan antes de anunciar el consumidor: se guardan para enviarlos después, en orden. */
		private final List<Runnable> beforeAnnouncement = new ArrayList<>();

		private ConsumerHandle handle;

		private boolean announced;

		private boolean stopped;

		private Long pendingTag;

		private long lastDeliveredTag;

		private ConsumerState(Session session, ConsumerInfo info) {
			this.session = session;
			this.info = info;
		}

		@Override
		public synchronized void onMessage(DeliveredMessage message) {
			if (stopped) {
				return;
			}
			lastDeliveredTag = message.deliveryTag();
			if (info.ackMode() == AckMode.MANUAL) {
				pendingTag = message.deliveryTag();
			}
			emit(() -> session.listener.messageReceived(info.id(), message));
		}

		@Override
		public void onStopped(StopReason reason) {
			session.consumers.remove(info.id());
			synchronized (this) {
				if (stopped) {
					return;
				}
				stopped = true;
				pendingTag = null;
				emit(() -> session.listener.consumerStopped(info.id(), reason, explain(reason)));
			}
		}

		/** Anuncia el consumidor y envía lo que llegó mientras tanto. */
		private synchronized void announce(ConsumerHandle startedHandle) {
			handle = startedHandle;
			if (session.closed) {
				stopped = true;
				startedHandle.stop();
				return;
			}
			session.listener.consumerStarted(info);
			announced = true;
			beforeAnnouncement.forEach(Runnable::run);
			beforeAnnouncement.clear();
		}

		private synchronized void stopByUser() {
			if (stopped) {
				return;
			}
			stopped = true;
			pendingTag = null;
			handle.stop();
			emit(() -> session.listener.consumerStopped(info.id(), StopReason.USER, explain(StopReason.USER)));
		}

		private void stopQuietly() {
			synchronized (this) {
				if (stopped) {
					return;
				}
				stopped = true;
				pendingTag = null;
			}
			if (handle != null) {
				try {
					handle.stop();
				}
				catch (RuntimeException exception) {
					// la sesión ya ha terminado: el cierre del canal en RabbitMQ devuelve los pendientes igualmente
				}
			}
		}

		private synchronized ActionOutcome resolve(long deliveryTag, MessageAction action) {
			if (info.ackMode() == AckMode.AUTOMATIC) {
				throw new ValidationException(
						"El consumidor «" + info.name() + "» confirma automáticamente: no hay mensajes que resolver.");
			}
			if (stopped) {
				throw consumerNotFound();
			}
			if (pendingTag == null || pendingTag != deliveryTag) {
				if (deliveryTag >= 1 && deliveryTag <= lastDeliveredTag) {
					throw new ConflictException("Este mensaje ya se ha resuelto.");
				}
				throw new NotFoundException("El mensaje no existe en este consumidor.");
			}
			ActionOutcome outcome = outcome(action);
			pendingTag = null;
			try {
				handle.resolve(deliveryTag, action);
			}
			catch (RuntimeException exception) {
				pendingTag = deliveryTag;
				throw exception;
			}
			return outcome;
		}

		private ActionOutcome outcome(MessageAction action) {
			return switch (action) {
				case ACK -> ActionOutcome.CONFIRMED;
				case REQUEUE -> ActionOutcome.REQUEUED;
				case REJECT -> hasDeadLetterQueue() ? ActionOutcome.DEAD_LETTERED : ActionOutcome.DISCARDED;
			};
		}

		/** Se consulta en el momento de rechazar: la dead-letter queue pudo eliminarse después de iniciar. */
		private boolean hasDeadLetterQueue() {
			List<Queue> queues = topology.listQueues();
			for (Queue queue : queues) {
				if (queue.name().equals(info.queue())) {
					String deadLetterQueue = queue.deadLetterQueue();
					return deadLetterQueue != null
							&& queues.stream().anyMatch(candidate -> candidate.name().equals(deadLetterQueue));
				}
			}
			return false;
		}

		private void emit(Runnable event) {
			if (!announced) {
				beforeAnnouncement.add(event);
			}
			else if (!session.closed) {
				event.run();
			}
		}

		private String explain(StopReason reason) {
			return switch (reason) {
				case USER -> "El consumidor «" + info.name() + "» se ha detenido.";
				case QUEUE_DELETED -> "La cola «" + info.queue() + "» se ha eliminado: el consumidor «" + info.name()
						+ "» ha terminado.";
				case CONNECTION_LOST -> "Se ha perdido la comunicación con RabbitMQ: el consumidor «" + info.name()
						+ "» ha terminado y sus mensajes pendientes de confirmar volverán a la cola.";
				case SESSION_CLOSED -> "La sesión ha terminado: el consumidor «" + info.name() + "» se ha detenido.";
			};
		}

	}

}
