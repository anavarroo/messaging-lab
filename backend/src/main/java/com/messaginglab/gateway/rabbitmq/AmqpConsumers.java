package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.gateway.BrokerConsumers;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.model.StopReason;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Consume por AMQP. Cada consumidor tiene su propio canal: cerrarlo es lo que devuelve a la cola los
 * mensajes pendientes de confirmar. En modo manual el canal usa {@code basicQos(1)}, de modo que RabbitMQ no
 * entrega el siguiente mensaje hasta resolver el actual.
 */
@Component
class AmqpConsumers implements BrokerConsumers {

	private final AmqpConnectionProvider connections;

	AmqpConsumers(AmqpConnectionProvider connections) {
		this.connections = connections;
	}

	@Override
	public ConsumerHandle start(String queue, AckMode ackMode, ConsumerListener listener) {
		Channel channel = null;
		try {
			channel = connections.connection().createChannel();
			AmqpConsumer consumer = new AmqpConsumer(channel, listener);
			if (ackMode == AckMode.MANUAL) {
				channel.basicQos(1);
			}
			channel.basicConsume(queue, ackMode == AckMode.AUTOMATIC, consumer::onDelivery, consumer::onCancel);
			channel.addShutdownListener(consumer::onShutdown);
			return consumer;
		}
		catch (IOException | RuntimeException exception) {
			closeQuietly(channel);
			throw BrokerErrorMapper.mapAmqp(exception);
		}
	}

	private static void closeQuietly(Channel channel) {
		if (channel != null && channel.isOpen()) {
			try {
				channel.close();
			}
			catch (IOException | TimeoutException | RuntimeException exception) {
				// ya se está cerrando: RabbitMQ devuelve a la cola lo pendiente igualmente
			}
		}
	}

	private static final class AmqpConsumer implements ConsumerHandle {

		private final Channel channel;

		private final ConsumerListener listener;

		/** Garantiza que el receptor se avisa de un fin ajeno al usuario una sola vez, y de ninguno si paró el usuario. */
		private final AtomicBoolean ended = new AtomicBoolean();

		private AmqpConsumer(Channel channel, ConsumerListener listener) {
			this.channel = channel;
			this.listener = listener;
		}

		private void onDelivery(String consumerTag, Delivery delivery) {
			listener.onMessage(new DeliveredMessage(delivery.getEnvelope().getDeliveryTag(), toMessage(delivery)));
		}

		/** RabbitMQ cancela el consumidor cuando se elimina su cola. */
		private void onCancel(String consumerTag) {
			if (ended.compareAndSet(false, true)) {
				closeQuietly(channel);
				listener.onStopped(StopReason.QUEUE_DELETED);
			}
		}

		private void onShutdown(ShutdownSignalException cause) {
			if (ended.compareAndSet(false, true)) {
				listener.onStopped(StopReason.CONNECTION_LOST);
			}
		}

		@Override
		public void resolve(long deliveryTag, MessageAction action) {
			try {
				switch (action) {
					case ACK -> channel.basicAck(deliveryTag, false);
					case REQUEUE -> channel.basicNack(deliveryTag, false, true);
					case REJECT -> channel.basicNack(deliveryTag, false, false);
				}
			}
			catch (IOException | RuntimeException exception) {
				throw BrokerErrorMapper.mapAmqp(exception);
			}
		}

		@Override
		public void stop() {
			if (ended.compareAndSet(false, true)) {
				closeQuietly(channel);
			}
		}

	}

	private static QueueMessage toMessage(Delivery delivery) {
		String exchange = delivery.getEnvelope().getExchange();
		String routingKey = delivery.getEnvelope().getRoutingKey();
		DeathInfo death = null;
		Map<String, Object> headers = delivery.getProperties().getHeaders();
		if (headers != null && headers.get("x-first-death-queue") != null
				&& headers.get("x-first-death-reason") != null) {
			String originQueue = headers.get("x-first-death-queue").toString();
			String reason = headers.get("x-first-death-reason").toString();
			death = new DeathInfo(originQueue, reason);
			Map<?, ?> original = originalDeath(headers.get("x-death"), originQueue, reason);
			if (original.get("exchange") != null) {
				exchange = original.get("exchange").toString();
			}
			if (original.get("routing-keys") instanceof List<?> keys && !keys.isEmpty() && keys.get(0) != null) {
				routingKey = keys.get(0).toString();
			}
		}
		return new QueueMessage(body(delivery.getBody()), exchange, routingKey, delivery.getEnvelope().isRedeliver(),
				death);
	}

	/** La entrada de {@code x-death} de la primera muerte del mensaje; si no se identifica, la más antigua. */
	private static Map<?, ?> originalDeath(Object xDeath, String originQueue, String reason) {
		Map<?, ?> oldest = Map.of();
		if (xDeath instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> death) {
					if (originQueue.equals(String.valueOf(death.get("queue")))
							&& reason.equals(String.valueOf(death.get("reason")))) {
						return death;
					}
					oldest = death;
				}
			}
		}
		return oldest;
	}

	/** Texto UTF-8; un contenido que no lo es se avisa como binario en base64, igual que en la inspección. */
	private static String body(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException exception) {
			return "[contenido binario, en base64] " + Base64.getEncoder().encodeToString(bytes);
		}
	}

}
