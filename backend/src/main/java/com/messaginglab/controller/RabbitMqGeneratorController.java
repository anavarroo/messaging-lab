package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqGeneratorApi;
import com.messaginglab.api.model.Emitter;
import com.messaginglab.api.model.EmitterDestination;
import com.messaginglab.api.model.EmitterRequest;
import com.messaginglab.api.model.GeneratorActionRequest;
import com.messaginglab.api.model.GeneratorSettings;
import com.messaginglab.api.model.GeneratorSettingsRequest;
import com.messaginglab.api.model.SimulatedConsumer;
import com.messaginglab.api.model.SimulatedConsumerRequest;
import com.messaginglab.api.model.TemplatePreset;
import com.messaginglab.api.model.TemplatePreviewRequest;
import com.messaginglab.api.model.TemplatePreviewResponse;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.generator.EmitterService;
import com.messaginglab.generator.EmitterSnapshot;
import com.messaginglab.generator.EmitterSpec;
import com.messaginglab.generator.GeneratorLimits;
import com.messaginglab.generator.IndustrialTemplates;
import com.messaginglab.generator.PayloadTemplate;
import com.messaginglab.generator.rabbitmq.RabbitMqDestination;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerService;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSnapshot;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec;
import com.messaginglab.generator.rabbitmq.SimulatedConsumerSpec.FailureAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa el generador de tráfico definido en el contrato OpenAPI: emisores y consumidores simulados que corren
 * en el backend. Traduce entre el contrato y el núcleo del generador.
 */
@RestController
public class RabbitMqGeneratorController implements RabbitMqGeneratorApi {

	private final EmitterService emitters;

	private final SimulatedConsumerService consumers;

	public RabbitMqGeneratorController(EmitterService emitters, SimulatedConsumerService consumers) {
		this.emitters = emitters;
		this.consumers = consumers;
	}

	// ---- emisores

	@Override
	public ResponseEntity<List<Emitter>> listEmitters() {
		return ResponseEntity.ok(emitters.list().stream().map(RabbitMqGeneratorController::toApi).toList());
	}

	@Override
	public ResponseEntity<Emitter> createEmitter(EmitterRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(toApi(emitters.create(toSpec(request))));
	}

	@Override
	public ResponseEntity<Emitter> updateEmitter(String id, EmitterRequest request) {
		return ResponseEntity.ok(toApi(emitters.update(id, toSpec(request))));
	}

	@Override
	public ResponseEntity<Void> deleteEmitter(String id) {
		emitters.delete(id);
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<Emitter> actOnEmitter(String id, GeneratorActionRequest request) {
		EmitterSnapshot result = switch (request.getAction()) {
			case START -> emitters.start(id);
			case PAUSE -> emitters.pause(id);
			case RESUME -> emitters.resume(id);
			case STOP -> emitters.stop(id);
		};
		return ResponseEntity.ok(toApi(result));
	}

	// ---- plantillas

	@Override
	public ResponseEntity<List<TemplatePreset>> listTemplates() {
		return ResponseEntity.ok(IndustrialTemplates.all()
			.stream()
			.map(preset -> new TemplatePreset(preset.id(), preset.name(), preset.description(), preset.template(),
					toApi(preset.suggestedInterval()), preset.suggestedRoutingKey(), preset.onlyWhenChanged()))
			.toList());
	}

	@Override
	public ResponseEntity<TemplatePreviewResponse> previewTemplate(TemplatePreviewRequest request) {
		String body = PayloadTemplate.parse(request.getTemplate())
			.generator(new Random(), Clock.systemUTC())
			.next();
		return ResponseEntity.ok(new TemplatePreviewResponse(body, body.getBytes(StandardCharsets.UTF_8).length));
	}

	// ---- ajustes

	@Override
	public ResponseEntity<GeneratorSettings> getGeneratorSettings() {
		return ResponseEntity.ok(settings());
	}

	@Override
	public ResponseEntity<GeneratorSettings> updateGeneratorSettings(GeneratorSettingsRequest request) {
		emitters.changeLimit(request.getMaxRatePerSecond());
		return ResponseEntity.ok(settings());
	}

	private GeneratorSettings settings() {
		return new GeneratorSettings(emitters.limits().maxRate(), GeneratorLimits.ABSOLUTE_MAX_RATE,
				emitters.ratePerSecondInUse(), EmitterService.MAX_EMITTERS, SimulatedConsumerService.MAX_CONSUMERS);
	}

	// ---- consumidores simulados

	@Override
	public ResponseEntity<List<SimulatedConsumer>> listSimulatedConsumers() {
		return ResponseEntity.ok(consumers.list().stream().map(RabbitMqGeneratorController::toApi).toList());
	}

	@Override
	public ResponseEntity<SimulatedConsumer> createSimulatedConsumer(SimulatedConsumerRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(toApi(consumers.create(toSpec(request))));
	}

	@Override
	public ResponseEntity<Void> deleteSimulatedConsumer(String id) {
		consumers.delete(id);
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<SimulatedConsumer> actOnSimulatedConsumer(String id, GeneratorActionRequest request) {
		SimulatedConsumerSnapshot result = switch (request.getAction()) {
			case START -> consumers.start(id);
			case STOP -> consumers.stop(id);
			case PAUSE, RESUME -> throw new ValidationException(
					"Los consumidores simulados solo admiten las acciones START y STOP.");
		};
		return ResponseEntity.ok(toApi(result));
	}

	@Override
	public ResponseEntity<Void> stopAllGenerators() {
		emitters.stopAll();
		consumers.stopAll();
		return ResponseEntity.ok().build();
	}

	// ---- traducciones

	private static EmitterSpec toSpec(EmitterRequest request) {
		return new EmitterSpec(request.getName(), toDestination(request.getDestination()),
				toInterval(request.getInterval(), "la frecuencia"), request.getTemplate(), request.getMaxMessages(),
				request.getMaxDurationSeconds(), Boolean.TRUE.equals(request.getOnlyWhenChanged()));
	}

	private static RabbitMqDestination toDestination(EmitterDestination destination) {
		return switch (destination.getType()) {
			case EXCHANGE -> RabbitMqDestination.toExchange(destination.getExchange(), destination.getRoutingKey());
			case QUEUE -> RabbitMqDestination.toQueue(destination.getQueue());
		};
	}

	private static com.messaginglab.generator.Interval toInterval(com.messaginglab.api.model.Interval interval, String subject) {
		return com.messaginglab.generator.Interval.of(
				com.messaginglab.generator.Interval.Mode.valueOf(interval.getMode().name()), interval.getFixedMs(),
				interval.getMinMs(), interval.getMaxMs(), subject);
	}

	private static com.messaginglab.api.model.Interval toApi(com.messaginglab.generator.Interval interval) {
		com.messaginglab.api.model.Interval result = new com.messaginglab.api.model.Interval(
				com.messaginglab.api.model.Interval.ModeEnum.valueOf(interval.mode().name()));
		if (interval.mode() == com.messaginglab.generator.Interval.Mode.FIXED) {
			return result.fixedMs(interval.fixedMs());
		}
		return result.minMs(interval.minMs()).maxMs(interval.maxMs());
	}

	private static EmitterDestination toApi(com.messaginglab.generator.Destination destination) {
		RabbitMqDestination target = (RabbitMqDestination) destination;
		EmitterDestination result = new EmitterDestination(EmitterDestination.TypeEnum.valueOf(target.type().name()));
		return target.type() == RabbitMqDestination.Type.QUEUE ? result.queue(target.queue())
				: result.exchange(target.exchange()).routingKey(target.routingKey());
	}

	private static Emitter toApi(EmitterSnapshot snapshot) {
		EmitterSpec spec = snapshot.spec();
		return new Emitter(snapshot.id(), spec.name(), toApi(spec.destination()), toApi(spec.interval()),
				spec.template(), spec.onlyWhenChanged(), Emitter.StatusEnum.valueOf(snapshot.status().name()),
				snapshot.published(), snapshot.unrouted(), snapshot.ratePerSecond())
			.maxMessages(spec.maxMessages())
			.maxDurationSeconds(spec.maxDurationSeconds())
			.stopMessage(snapshot.stopMessage());
	}

	private static SimulatedConsumerSpec toSpec(SimulatedConsumerRequest request) {
		return new SimulatedConsumerSpec(request.getName(), request.getQueue(),
				toInterval(request.getProcessing(), "el tiempo de proceso"), request.getFailurePercent(),
				FailureAction.valueOf(request.getOnFailure().name()));
	}

	private static SimulatedConsumer toApi(SimulatedConsumerSnapshot snapshot) {
		SimulatedConsumerSpec spec = snapshot.spec();
		return new SimulatedConsumer(snapshot.id(), spec.name(), spec.queue(), toApi(spec.processing()),
				spec.failurePercent(), SimulatedConsumer.OnFailureEnum.valueOf(spec.onFailure().name()),
				SimulatedConsumer.StatusEnum.valueOf(snapshot.status().name()), snapshot.processed(), snapshot.failed(),
				snapshot.ratePerSecond())
			.stopMessage(snapshot.stopMessage());
	}

}
