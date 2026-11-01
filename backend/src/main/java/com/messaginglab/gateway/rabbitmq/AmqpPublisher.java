package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.gateway.BrokerPublisher;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Publica por AMQP. Cada publicación usa su propio canal con confirmaciones y {@code mandatory=true}: si
 * ninguna cola recibe el mensaje, RabbitMQ lo devuelve antes de confirmarlo, y así se distingue «publicado y
 * enrutado» de «aceptado pero descartado».
 */
@Component
class AmqpPublisher implements BrokerPublisher {

	private static final long CONFIRMATION_TIMEOUT_MILLIS = 10_000;

	private static final AMQP.BasicProperties PERSISTENT_TEXT = new AMQP.BasicProperties.Builder()
		.contentType("text/plain")
		.contentEncoding("UTF-8")
		.deliveryMode(MessageProperties.PERSISTENT_TEXT_PLAIN.getDeliveryMode())
		.build();

	private final AmqpConnectionProvider connections;

	AmqpPublisher(AmqpConnectionProvider connections) {
		this.connections = connections;
	}

	@Override
	public PublishResult publish(PublishRequest request) {
		try (Channel channel = connections.connection().createChannel()) {
			AtomicBoolean returned = new AtomicBoolean();
			channel.addReturnListener(unroutable -> returned.set(true));
			channel.confirmSelect();
			AMQP.BasicProperties properties = request.source() == null ? PERSISTENT_TEXT
					: PERSISTENT_TEXT.builder().appId(request.source()).build();
			channel.basicPublish(request.exchange(), request.routingKey(), true, properties,
					request.body().getBytes(StandardCharsets.UTF_8));
			if (!channel.waitForConfirms(CONFIRMATION_TIMEOUT_MILLIS)) {
				throw new BrokerRejectedException("RabbitMQ no ha aceptado el mensaje.");
			}
			return new PublishResult(!returned.get());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BrokerUnavailableException("La publicación se ha interrumpido.", exception);
		}
		catch (IOException | TimeoutException | RuntimeException exception) {
			throw BrokerErrorMapper.mapAmqp(exception);
		}
	}

}
