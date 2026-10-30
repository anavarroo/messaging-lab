package com.messaginglab.generator.rabbitmq;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.gateway.BrokerPublisher;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.generator.Destination;
import com.messaginglab.generator.MessagePublisher;
import com.messaginglab.model.PublishRequest;

/**
 * Adaptador de los emisores sobre RabbitMQ. Publica directamente con el puerto {@link BrokerPublisher}, sin pasar
 * por {@code PublishService}: este comprueba que el exchange exista consultando la API de gestión en cada mensaje,
 * algo inasumible a cien mensajes por segundo. Aquí la existencia del destino se comprueba al poner el emisor en
 * marcha, y si desaparece después lo notifica RabbitMQ al publicar.
 */
public final class RabbitMqMessagePublisher implements MessagePublisher {

	private final BrokerPublisher publisher;

	private final BrokerTopology topology;

	public RabbitMqMessagePublisher(BrokerPublisher publisher, BrokerTopology topology) {
		this.publisher = publisher;
		this.topology = topology;
	}

	@Override
	public void verify(Destination destination) {
		RabbitMqDestination target = cast(destination);
		if (target.type() == RabbitMqDestination.Type.QUEUE) {
			if (topology.listQueues().stream().noneMatch(queue -> queue.name().equals(target.queue()))) {
				throw new NotFoundException("La cola «" + target.queue() + "» no existe.");
			}
		}
		else if (topology.listExchanges().stream().noneMatch(exchange -> exchange.name().equals(target.exchange()))) {
			throw new NotFoundException("El exchange «" + target.exchange() + "» no existe.");
		}
	}

	@Override
	public boolean publish(Destination destination, String body) {
		RabbitMqDestination target = cast(destination);
		boolean routed = publisher.publish(new PublishRequest(target.publishExchange(), target.publishRoutingKey(), body))
			.routed();
		if (!routed && target.type() == RabbitMqDestination.Type.QUEUE) {
			// por el exchange por defecto un mensaje solo deja de enrutarse si la cola ya no existe
			throw new NotFoundException("La cola «" + target.queue() + "» ya no existe.");
		}
		return routed;
	}

	private static RabbitMqDestination cast(Destination destination) {
		if (destination instanceof RabbitMqDestination target) {
			return target;
		}
		throw new IllegalArgumentException("Este publicador solo admite destinos de RabbitMQ.");
	}

}
