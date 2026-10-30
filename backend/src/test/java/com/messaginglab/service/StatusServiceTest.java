package com.messaginglab.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.model.ApplicationStatus;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class StatusServiceTest {

	@Test
	void getStatusReturnsAvailableWithNameAndVersionFromBuildProperties() {
		Properties entries = new Properties();
		entries.setProperty("name", "test-app");
		entries.setProperty("version", "9.8.7");
		StatusService service = new StatusService(new BuildProperties(entries));

		ApplicationStatus status = service.getStatus();

		assertThat(status.status()).isEqualTo(ApplicationStatus.State.AVAILABLE);
		assertThat(status.name()).isEqualTo("test-app");
		assertThat(status.version()).isEqualTo("9.8.7");
	}

}
