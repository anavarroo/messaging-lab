package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.flow.FlowService;
import com.messaginglab.gateway.BrokerTopology;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta el núcleo del visualizador de flujo con el trazado de RabbitMQ. */
@Configuration
class RabbitMqFlowConfiguration {

	@Bean
	RabbitMqFlowTopology rabbitMqFlowTopology(BrokerTopology broker, ManagementApiClient management) {
		return new RabbitMqFlowTopology(broker, management, Clock.systemUTC());
	}

	@Bean(destroyMethod = "shutdown")
	ScheduledExecutorService flowTicker() {
		return Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "flow-tick");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Bean(destroyMethod = "stop")
	RabbitMqTraceSource rabbitMqTraceSource(RabbitMqProperties properties, ManagementApiClient management,
			RabbitMqFlowTopology topology) {
		return new RabbitMqTraceSource(properties, management, topology, Clock.systemUTC());
	}

	/** El servicio solo trabaja cuando hay una pantalla abierta; el reloj de cada segundo es barato. */
	@Bean(destroyMethod = "")
	FlowService rabbitMqFlowService(RabbitMqTraceSource source, RabbitMqFlowTopology topology,
			ScheduledExecutorService flowTicker) {
		FlowService service = new FlowService(source, topology, Clock.systemUTC());
		flowTicker.scheduleAtFixedRate(() -> {
			try {
				service.tick();
			}
			catch (RuntimeException exception) {
				// un fallo puntual no debe detener el reloj de los siguientes segundos
			}
		}, 1, 1, TimeUnit.SECONDS);
		return service;
	}

	@Bean
	com.messaginglab.diagnostics.SnapshotProvider labSnapshotProvider(BrokerTopology broker, ManagementApiClient management,
			com.messaginglab.generator.EmitterService emitters,
			com.messaginglab.generator.rabbitmq.SimulatedConsumerService consumers, FlowService flow) {
		return new RabbitMqSnapshotProvider(broker, management, emitters, consumers, flow, Clock.systemUTC());
	}

}
