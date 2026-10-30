package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RabbitMqPropertiesTest {

	/** Carga application.properties sin leer el infra/.env real de quien ejecute la prueba. */
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(RabbitMqConfiguration.class)
		.withPropertyValues("spring.config.import=optional:file:./no-existe.env[.properties]");

	@Test
	void usesTheDefaultValuesWhenNothingIsConfigured() {
		runner.run(context -> {
			RabbitMqProperties properties = context.getBean(RabbitMqProperties.class);

			assertThat(properties.host()).isEqualTo("localhost");
			assertThat(properties.port()).isEqualTo(5672);
			assertThat(properties.managementPort()).isEqualTo(15672);
			assertThat(properties.username()).isEqualTo("guest");
			assertThat(properties.password()).isEqualTo("guest");
			assertThat(properties.virtualHost()).isEqualTo("/");
		});
	}

	@Test
	void environmentVariablesReplaceTheDefaults() {
		runner
			.withPropertyValues("RABBITMQ_HOST=rabbitmq", "RABBITMQ_PORT=5673", "RABBITMQ_MANAGEMENT_PORT=15673",
					"RABBITMQ_USER=lab", "RABBITMQ_PASSWORD=secreto-de-prueba")
			.run(context -> {
				RabbitMqProperties properties = context.getBean(RabbitMqProperties.class);

				assertThat(properties.host()).isEqualTo("rabbitmq");
				assertThat(properties.port()).isEqualTo(5673);
				assertThat(properties.managementPort()).isEqualTo(15673);
				assertThat(properties.username()).isEqualTo("lab");
				assertThat(properties.password()).isEqualTo("secreto-de-prueba");
			});
	}

	@Test
	void toStringDoesNotRevealThePassword() {
		RabbitMqProperties properties = new RabbitMqProperties("localhost", 5672, 15672, "lab", "secreto-de-prueba",
				"/");

		assertThat(properties.toString()).doesNotContain("secreto-de-prueba").contains("password=***");
	}

}
