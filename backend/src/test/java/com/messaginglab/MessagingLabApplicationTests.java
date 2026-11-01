package com.messaginglab;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MessagingLabApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BuildProperties buildProperties;

	@Test
	void getStatusReturnsNameAndVersionFromBuildInfo() throws Exception {
		mockMvc.perform(get("/api/status"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.name").value(buildProperties.getName()))
			.andExpect(jsonPath("$.version").value(buildProperties.getVersion()));
	}

}
