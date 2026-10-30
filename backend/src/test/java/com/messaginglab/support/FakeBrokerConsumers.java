package com.messaginglab.support;

import com.messaginglab.gateway.BrokerConsumers;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.DeliveredMessage;
import com.messaginglab.model.MessageAction;
import com.messaginglab.model.QueueMessage;
import com.messaginglab.model.StopReason;
import java.util.ArrayList;
import java.util.List;

/** Consumidores falsos: el test decide cuándo llegan mensajes y cuándo termina el consumidor. */
public class FakeBrokerConsumers implements BrokerConsumers {

	private final List<FakeConsumer> started = new ArrayList<>();

	private RuntimeException startFailure;

	public void failStartWith(RuntimeException failure) {
		this.startFailure = failure;
	}

	public List<FakeConsumer> started() {
		return started;
	}

	public FakeConsumer last() {
		return started.get(started.size() - 1);
	}

	@Override
	public ConsumerHandle start(String queue, AckMode ackMode, ConsumerListener listener) {
		if (startFailure != null) {
			throw startFailure;
		}
		FakeConsumer consumer = new FakeConsumer(queue, ackMode, listener);
		started.add(consumer);
		return consumer;
	}

	public static final class FakeConsumer implements ConsumerHandle {

		public final String queue;

		public final AckMode ackMode;

		private final ConsumerListener listener;

		private final List<String> resolutions = new ArrayList<>();

		private boolean stopped;

		private long nextTag = 1;

		private RuntimeException resolveFailure;

		private FakeConsumer(String queue, AckMode ackMode, ConsumerListener listener) {
			this.queue = queue;
			this.ackMode = ackMode;
			this.listener = listener;
		}

		/** RabbitMQ entrega un mensaje; devuelve su deliveryTag. */
		public long deliver(String body) {
			long tag = nextTag++;
			listener.onMessage(new DeliveredMessage(tag, new QueueMessage(body, "ex", "rk", false, null)));
			return tag;
		}

		/** El consumidor termina por una causa ajena al usuario. */
		public void brokerStops(StopReason reason) {
			listener.onStopped(reason);
		}

		public void failResolveWith(RuntimeException failure) {
			this.resolveFailure = failure;
		}

		public List<String> resolutions() {
			return resolutions;
		}

		public boolean stopped() {
			return stopped;
		}

		@Override
		public void resolve(long deliveryTag, MessageAction action) {
			if (resolveFailure != null) {
				throw resolveFailure;
			}
			resolutions.add(deliveryTag + ":" + action);
		}

		@Override
		public void stop() {
			stopped = true;
		}

	}

}
