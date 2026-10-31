package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqFlowApi;
import com.messaginglab.api.model.FlowEdge;
import com.messaginglab.api.model.FlowEvent;
import com.messaginglab.api.model.FlowNode;
import com.messaginglab.api.model.FlowSourceStatus;
import com.messaginglab.api.model.FlowTick;
import com.messaginglab.api.model.FlowTopology;
import com.messaginglab.flow.FlowMessage;
import com.messaginglab.flow.FlowService;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Visualizador de flujo de RabbitMQ: traduce entre el contrato y el núcleo de {@code com.messaginglab.flow}. */
@RestController
public class RabbitMqFlowController implements RabbitMqFlowApi {

	static final String STATUS = "status";

	static final String TOPOLOGY = "topology";

	static final String TICK = "tick";

	static final String FLOW_EVENT = "flow-event";

	private static final int HEARTBEAT_SECONDS = 15;

	private final FlowService flow;

	public RabbitMqFlowController(FlowService flow) {
		this.flow = flow;
	}

	@Override
	public ResponseEntity<FlowSourceStatus> getFlowStatus() {
		return ResponseEntity.ok(toApi(flow.status()));
	}

	@Override
	public ResponseEntity<FlowTopology> getFlowTopology() {
		return ResponseEntity.ok(toApi(flow.topology()));
	}

	@Override
	public ResponseEntity<List<FlowEvent>> listFlowEvents(Integer limit) {
		return ResponseEntity.ok(flow.recentEvents(limit).stream().map(RabbitMqFlowController::toApi).toList());
	}

	@Override
	public ResponseEntity<SseEmitter> openFlowStream() {
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream(new SseEmitter(0L)));
	}

	/**
	 * Envía a la pantalla lo que llega al servicio. Un hilo virtual por pantalla lee su cola de mensajes, de modo que
	 * una pantalla lenta no frena a las demás ni al servicio. Separado para poder probarlo con un emisor falso.
	 */
	SseEmitter stream(SseEmitter emitter) {
		FlowService.Subscription subscription = flow.subscribe();
		AtomicBoolean open = new AtomicBoolean(true);
		Thread sender = Thread.ofVirtual().name("flow-stream").unstarted(() -> {
			try {
				while (open.get()) {
					FlowMessage message = subscription.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
					if (message == null) {
						emitter.send(SseEmitter.event().comment("latido"));
					}
					else {
						send(emitter, message);
					}
				}
			}
			catch (IOException | RuntimeException exception) {
				emitter.completeWithError(exception);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			finally {
				subscription.close();
			}
		});
		Runnable close = () -> {
			open.set(false);
			sender.interrupt();
		};
		emitter.onCompletion(close);
		emitter.onTimeout(close);
		emitter.onError(error -> close.run());
		sender.start();
		return emitter;
	}

	private static void send(SseEmitter emitter, FlowMessage message) throws IOException {
		switch (message) {
			case FlowMessage.Event event -> emitter.send(SseEmitter.event().name(FLOW_EVENT).data(toApi(event.event()), MediaType.APPLICATION_JSON));
			case FlowMessage.Tick tick -> emitter.send(SseEmitter.event().name(TICK).data(toApi(tick.tick()), MediaType.APPLICATION_JSON));
			case FlowMessage.Status status -> emitter.send(SseEmitter.event().name(STATUS).data(toApi(status.status()), MediaType.APPLICATION_JSON));
			case FlowMessage.Topology topology ->
				emitter.send(SseEmitter.event().name(TOPOLOGY).data(toApi(topology.topology()), MediaType.APPLICATION_JSON));
		}
	}

	static FlowSourceStatus toApi(com.messaginglab.flow.FlowSourceStatus status) {
		return new FlowSourceStatus(FlowSourceStatus.StateEnum.valueOf(status.state().name())).hint(status.hint());
	}

	static FlowTopology toApi(com.messaginglab.flow.FlowTopology topology) {
		return new FlowTopology(
				topology.nodes()
					.stream()
					.map(node -> new FlowNode(node.id(), FlowNode.KindEnum.valueOf(node.kind().name()), node.label())
						.detail(node.detail()))
					.toList(),
				topology.edges().stream().map(edge -> new FlowEdge(edge.from(), edge.to()).label(edge.label())).toList());
	}

	static FlowTick toApi(com.messaginglab.flow.FlowTick tick) {
		return new FlowTick(utc(tick.time()), tick.published(), tick.delivered(), tick.ready(), tick.omitted());
	}

	static FlowEvent toApi(com.messaginglab.flow.FlowEvent event) {
		return new FlowEvent(event.id(), utc(event.time()), FlowEvent.TypeEnum.valueOf(event.type().name()),
				event.destinations(), event.sizeBytes(), event.redelivered(), event.unrouted())
			.producer(event.producer())
			.router(event.router())
			.consumer(event.consumer())
			.key(event.key())
			.body(event.body())
			.bodyTruncated(event.bodyTruncated())
			.properties(event.properties());
	}

	private static OffsetDateTime utc(java.time.Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
