package com.messaginglab.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.model.ApplicationStatus;
import com.messaginglab.service.StatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatusController.class)
class StatusControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StatusService statusService;

	@Test
	void getStatusReturnsStatusNameAndVersion() throws Exception {
		given(statusService.getStatus())
			.willReturn(new ApplicationStatus(ApplicationStatus.State.AVAILABLE, "test-app", "9.8.7"));

		mockMvc.perform(get("/api/status"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.name").value("test-app"))
			.andExpect(jsonPath("$.version").value("9.8.7"));
	}

}
