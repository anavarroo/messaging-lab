package com.messaginglab.controller;

import com.messaginglab.api.LabApi;
import com.messaginglab.api.model.LabActivity;
import com.messaginglab.api.model.LabDiagnostics;
import com.messaginglab.api.model.LabFinding;
import com.messaginglab.api.model.LabSnapshot;
import com.messaginglab.api.model.LabSnapshotBroker;
import com.messaginglab.api.model.LabSnapshotConsumersInner;
import com.messaginglab.api.model.LabSnapshotEmittersInner;
import com.messaginglab.api.model.LabSnapshotExchangesInner;
import com.messaginglab.api.model.LabSnapshotLoad;
import com.messaginglab.api.model.LabSnapshotQueuesInner;
import com.messaginglab.api.model.LabSnapshotTrace;
import com.messaginglab.diagnostics.Activity;
import com.messaginglab.diagnostics.Diagnostics;
import com.messaginglab.diagnostics.Snapshot;
import com.messaginglab.diagnostics.SnapshotProvider;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Foto, diagnóstico y actividad del laboratorio, de solo lectura. Los consulta el asistente de IA. */
@RestController
public class LabController implements LabApi {

	private final SnapshotProvider snapshots;

	public LabController(SnapshotProvider snapshots) {
		this.snapshots = snapshots;
	}

	@Override
	public ResponseEntity<LabDiagnostics> getLabDiagnostics() {
		Snapshot snapshot = snapshots.current();
		return ResponseEntity.ok(new LabDiagnostics(snapshot.broker().available(), Diagnostics.analyze(snapshot)
			.stream()
			.map(finding -> new LabFinding(LabFinding.SeverityEnum.valueOf(finding.severity().name()), finding.topic().name(),
					finding.text()))
			.toList()));
	}

	@Override
	public ResponseEntity<LabActivity> getLabActivity() {
		Snapshot snapshot = snapshots.current();
		return ResponseEntity.ok(new LabActivity(Activity.active(snapshot), Activity.describe(snapshot)));
	}

	@Override
	public ResponseEntity<LabSnapshot> getLabSnapshot() {
		Snapshot snapshot = snapshots.current();
		return ResponseEntity.ok(new LabSnapshot().time(snapshot.time().atOffset(ZoneOffset.UTC))
			.broker(new LabSnapshotBroker(snapshot.broker().available()).version(snapshot.broker().version())
				.reason(snapshot.broker().reason()))
			.trace(new LabSnapshotTrace(LabSnapshotTrace.StateEnum.valueOf(snapshot.trace().state().name()))
				.hint(snapshot.trace().hint()))
			.queues(snapshot.queues()
				.stream()
				.map(queue -> new LabSnapshotQueuesInner(queue.name(), queue.ready(), queue.unacknowledged(), queue.consumers())
					.deadLetterQueue(queue.deadLetterQueue())
					.inPerSecond(queue.inPerSecond())
					.outPerSecond(queue.outPerSecond()))
				.toList())
			.exchanges(snapshot.exchanges()
				.stream()
				.map(exchange -> new LabSnapshotExchangesInner(exchange.name(), exchange.type(), exchange.boundQueues()))
				.toList())
			.emitters(snapshot.emitters()
				.stream()
				.map(emitter -> new LabSnapshotEmittersInner(emitter.name(), emitter.status().name(), emitter.destination(),
						emitter.ratePerSecond(), emitter.published(), emitter.unrouted())
					.stopMessage(emitter.stopMessage()))
				.toList())
			.consumers(snapshot.consumers()
				.stream()
				.map(consumer -> new LabSnapshotConsumersInner(consumer.name(), consumer.queue(), consumer.status().name(),
						consumer.ratePerSecond(), consumer.processed(), consumer.failed())
					.stopMessage(consumer.stopMessage()))
				.toList())
			.load(new LabSnapshotLoad(snapshot.load().ratePerSecondInUse(), snapshot.load().maxRatePerSecond(),
					snapshot.load().omittedPerSecond())));
	}

}
