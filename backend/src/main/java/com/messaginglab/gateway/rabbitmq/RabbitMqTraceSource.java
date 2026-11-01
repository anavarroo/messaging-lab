package com.messaginglab.gateway.rabbitmq;

import com.messaginglab.exception.LabException;
import com.messaginglab.flow.FlowEvent;
import com.messaginglab.flow.FlowSource;
import com.messaginglab.flow.FlowSourceStatus;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Fuente de eventos del visualizador: el trazado (firehose) de RabbitMQ. Con el trazado activado, RabbitMQ copia cada
 * publicación y cada entrega al exchange {@code amq.rabbitmq.trace}; esta clase escucha ese exchange con una cola
 * propia, exclusiva y acotada, que desaparece sola con la conexión.
 * <p>
 * Usa su propia conexión AMQP para que un fallo del visualizador no afecte a los consumidores del laboratorio.
 */
final class RabbitMqTraceSource implements FlowSource {

	static final String TRACE_EXCHANGE = "amq.rabbitmq.trace";

	static final String QUEUE_PREFIX = "ml.flow.trace.";

	/** Mensajes que puede acumular la cola propia antes de descartar los más antiguos. */
	private static final int QUEUE_LIMIT = 5_000;

	private static final String ENABLE_HINT = "El trazado de RabbitMQ está desactivado. El Docker Compose del proyecto lo activa "
			+ "solo; si usas otro RabbitMQ, ejecuta «rabbitmqctl trace_on» (con Docker: "
			+ "«docker compose -f infra/docker-compose.yml exec rabbitmq rabbitmqctl trace_on»).";

	private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
	};

	private final ConnectionFactory factory;

	private final ManagementApiClient management;

	private final RabbitMqFlowTopology topology;

	private final Clock clock;

	private Connection connection;

	private volatile boolean lost;

	RabbitMqTraceSource(RabbitMqProperties properties, ManagementApiClient management, RabbitMqFlowTopology topology,
			Clock clock) {
		this.factory = new ConnectionFactory();
		factory.setHost(properties.host());
		factory.setPort(properties.port());
		factory.setUsername(properties.username());
		factory.setPassword(properties.password());
		factory.setVirtualHost(properties.virtualHost());
		factory.setAutomaticRecoveryEnabled(false);
		factory.setTopologyRecoveryEnabled(false);
		factory.setConnectionTimeout(5_000);
		factory.setHandshakeTimeout(5_000);
		this.management = management;
		this.topology = topology;
		this.clock = clock;
	}

	/**
	 * Si la escucha se ha perdido (por ejemplo, RabbitMQ se reinició), informa una vez de que no está disponible; el
	 * servicio la para y, al volver a ser activa, la arranca de nuevo.
	 */
	@Override
	public FlowSourceStatus status() {
		if (lost) {
			lost = false;
			return new FlowSourceStatus(FlowSourceStatus.State.UNAVAILABLE, "Se ha perdido la conexión con RabbitMQ; reintentando.");
		}
		try {
			Map<String, Object> vhost = management.get("/vhosts/{vhost}", OBJECT, management.virtualHost());
			boolean tracing = vhost != null && Boolean.TRUE.equals(vhost.get("tracing"));
			return tracing ? new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null)
					: new FlowSourceStatus(FlowSourceStatus.State.DISABLED, ENABLE_HINT);
		}
		catch (LabException exception) {
			return new FlowSourceStatus(FlowSourceStatus.State.UNAVAILABLE, "RabbitMQ no está disponible.");
		}
	}

	@Override
	public synchronized void start(Consumer<FlowEvent> sink) {
		stop();
		String queue = QUEUE_PREFIX + UUID.randomUUID();
		TraceEventTranslator translator = new TraceEventTranslator(clock, queue);
		try {
			connection = factory.newConnection("messaging-lab-flow");
			connection.addShutdownListener(cause -> lost = true);
			Channel channel = connection.createChannel();
			channel.queueDeclare(queue, false, true, true, Map.of("x-max-length", QUEUE_LIMIT, "x-overflow", "drop-head"));
			channel.queueBind(queue, TRACE_EXCHANGE, "#");
			channel.basicConsume(queue, true, (tag, delivery) -> translator
				.translate(delivery.getEnvelope().getRoutingKey(), delivery.getProperties().getHeaders(), delivery.getBody())
				.ifPresent(event -> {
					topology.observe(event);
					sink.accept(event);
				}), tag -> lost = true);
		}
		catch (IOException | TimeoutException | RuntimeException exception) {
			lost = true;
			stop();
		}
	}

	@Override
	public synchronized void stop() {
		if (connection != null) {
			Connection closing = connection;
			connection = null;
			try {
				if (closing.isOpen()) {
					closing.close();
				}
			}
			catch (IOException | RuntimeException exception) {
				// la cola es exclusiva: RabbitMQ la elimina al perderse la conexión, tanto si se cierra bien como si no
			}
		}
	}

}
