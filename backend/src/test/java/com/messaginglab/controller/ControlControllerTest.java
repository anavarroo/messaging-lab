package com.messaginglab.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.control.ServiceControl;
import com.messaginglab.control.ServiceControl.Action;
import com.messaginglab.control.ServiceControl.Service;
import com.messaginglab.control.ServiceControl.ServiceState;
import com.messaginglab.control.ServiceControl.State;
import com.messaginglab.exception.ControlUnavailableException;
import com.messaginglab.exception.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ControlController.class)
class ControlControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ServiceControl control;

	@Test
	void returnsTheStateOfEachService() throws Exception {
		given(control.status()).willReturn(new ServiceControl.Status(true, null, List.of(
				new ServiceState(Service.RABBITMQ, State.RUNNING, null), new ServiceState(Service.ASSISTANT, State.MISSING, "No está."))));

		mockMvc.perform(get("/api/control/services"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.services[0].service").value("RABBITMQ"))
			.andExpect(jsonPath("$.services[0].state").value("RUNNING"))
			.andExpect(jsonPath("$.services[1].state").value("MISSING"))
			.andExpect(jsonPath("$.services[1].message").value("No está."));
	}

	@Test
	void explainsWhyTheControlIsNotAvailable() throws Exception {
		given(control.status()).willReturn(new ServiceControl.Status(false, ServiceControl.NO_COMPOSE, List.of()));

		mockMvc.perform(get("/api/control/services"))
			.andExpect(jsonPath("$.available").value(false))
			.andExpect(jsonPath("$.reason").value(ServiceControl.NO_COMPOSE))
			.andExpect(jsonPath("$.services.length()").value(0));
	}

	@Test
	void stopsAService() throws Exception {
		given(control.act("rabbitmq", Action.STOP)).willReturn(new ServiceState(Service.RABBITMQ, State.STOPPED, null));

		act("rabbitmq", "{\"action\":\"STOP\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.service").value("RABBITMQ"))
			.andExpect(jsonPath("$.state").value("STOPPED"));
	}

	@Test
	void rejectsAnUnknownActionAnyOtherServiceAndNoCompose() throws Exception {
		act("rabbitmq", "{\"action\":\"DELETE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));

		given(control.act("backend", Action.STOP)).willThrow(new NotFoundException("Solo se pueden parar y levantar RabbitMQ y el asistente de IA."));
		act("backend", "{\"action\":\"STOP\"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

		given(control.act("assistant", Action.START)).willThrow(new ControlUnavailableException(ServiceControl.NO_COMPOSE));
		act("assistant", "{\"action\":\"START\"}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("CONTROL_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value(ServiceControl.NO_COMPOSE));
	}

	private ResultActions act(String service, String body) throws Exception {
		return mockMvc.perform(post("/api/control/services/" + service + "/actions").contentType(MediaType.APPLICATION_JSON).content(body));
	}

}
