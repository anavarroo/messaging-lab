package com.messaginglab.controller;

import static com.messaginglab.diagnostics.SnapshotTestData.QUIET;
import static com.messaginglab.diagnostics.SnapshotTestData.emitter;
import static com.messaginglab.diagnostics.SnapshotTestData.queue;
import static com.messaginglab.diagnostics.SnapshotTestData.snapshot;
import static com.messaginglab.diagnostics.SnapshotTestData.with;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.diagnostics.Snapshot;
import com.messaginglab.diagnostics.Snapshot.Run;
import com.messaginglab.diagnostics.SnapshotProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LabController.class)
class LabControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SnapshotProvider snapshots;

	@Test
	void returnsTheFindingsFromMostToLeastSevere() throws Exception {
		given(snapshots.current()).willReturn(snapshot(List.of(queue("barra", 350, 0, 1.0, 0.0), queue("cocina", 100, 1, 1.0, 11.0)),
				List.of(emitter("Brazo", Run.FAILED, 0, 0, "La cola «x» ya no existe.")), List.of()));

		mockMvc.perform(get("/api/lab/diagnostics"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.brokerAvailable").value(true))
			.andExpect(jsonPath("$.findings.length()").value(3))
			.andExpect(jsonPath("$.findings[0].severity").value("CRITICAL"))
			.andExpect(jsonPath("$.findings[0].topic").value("EMITTERS"))
			.andExpect(jsonPath("$.findings[1].text").value(org.hamcrest.Matchers.startsWith("CAUSA: la cola «barra» no se vacía")))
			.andExpect(jsonPath("$.findings[2].severity").value("INFO"));
	}

	@Test
	void anEmptyListWhenNothingIsWrong() throws Exception {
		given(snapshots.current()).willReturn(snapshot(List.of(), List.of(), List.of()));

		mockMvc.perform(get("/api/lab/diagnostics")).andExpect(jsonPath("$.findings.length()").value(0));
	}

	@Test
	void saysRabbitMqIsDownInBothOperations() throws Exception {
		given(snapshots.current()).willReturn(with(new Snapshot.Broker(false, null, null), new Snapshot.Trace(Snapshot.Trace.State.UNKNOWN, null), QUIET));

		mockMvc.perform(get("/api/lab/diagnostics"))
			.andExpect(jsonPath("$.brokerAvailable").value(false))
			.andExpect(jsonPath("$.findings[0].topic").value("STATE"));
		mockMvc.perform(get("/api/lab/activity"))
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith("CAUSA: RabbitMQ no está disponible")));
	}

	@Test
	void returnsTheActivity() throws Exception {
		given(snapshots.current()).willReturn(snapshot(List.of(queue("cocina", 40, 1, 10.0, 5.0)), List.of(emitter("Brazo", Run.RUNNING, 10, 0, null)), List.of()));

		mockMvc.perform(get("/api/lab/activity"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("el emisor «Brazo» publica 10 mensajes por segundo")));
	}

	@Test
	void returnsTheWholeSnapshotWithoutMessageBodies() throws Exception {
		given(snapshots.current()).willReturn(snapshot(List.of(queue("cocina", 40, 1, 10.0, 5.0)), List.of(emitter("Brazo", Run.RUNNING, 10, 0, null)), List.of()));

		mockMvc.perform(get("/api/lab/snapshot"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.broker.available").value(true))
			.andExpect(jsonPath("$.queues[0].name").value("cocina"))
			.andExpect(jsonPath("$.queues[0].ready").value(40))
			.andExpect(jsonPath("$.queues[0].consumers").value(1))
			.andExpect(jsonPath("$.queues[0].inPerSecond").value(10.0))
			.andExpect(jsonPath("$.emitters[0].status").value("RUNNING"))
			.andExpect(jsonPath("$.emitters[0].ratePerSecond").value(10.0))
			.andExpect(jsonPath("$.load.maxRatePerSecond").isNumber());
	}

}
