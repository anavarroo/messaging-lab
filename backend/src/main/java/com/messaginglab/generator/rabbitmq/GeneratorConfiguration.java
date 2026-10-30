package com.messaginglab.generator.rabbitmq;

import com.messaginglab.gateway.BrokerConsumers;
import com.messaginglab.gateway.BrokerPublisher;
import com.messaginglab.gateway.BrokerTopology;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.ThreadPoolScheduler;
import java.time.Clock;
import java.util.SplittableRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conecta el núcleo del generador, que no sabe nada de RabbitMQ, con su adaptador de RabbitMQ. Es la única clase
 * del generador que usa Spring.
 */
@Configuration
class GeneratorConfiguration {

	/** Hilos que ejecutan las publicaciones de los emisores: cada una dura unos milisegundos. */
	private static final int EMITTER_THREADS = 8;

	/** Hilos de los consumidores simulados: solo resuelven mensajes cuando acaba su tiempo de proceso. */
	private static final int CONSUMER_THREADS = 4;

	@Bean
	GeneratorLimits generatorLimits() {
		return new GeneratorLimits();
	}

	@Bean(destroyMethod = "shutdown")
	EmitterService emitterService(BrokerPublisher publisher, BrokerTopology topology, GeneratorLimits limits) {
		return new EmitterService(new RabbitMqMessagePublisher(publisher, topology), new ThreadPoolScheduler(EMITTER_THREADS),
				Clock.systemUTC(), () -> new SplittableRandom(), limits);
	}

	@Bean(destroyMethod = "shutdown")
	SimulatedConsumerService simulatedConsumerService(BrokerConsumers consumers) {
		return new SimulatedConsumerService(consumers, new ThreadPoolScheduler(CONSUMER_THREADS), Clock.systemUTC(),
				() -> new SplittableRandom());
	}

}
