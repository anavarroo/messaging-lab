package com.messaginglab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que la infraestructura de las pruebas de integración funciona: Docker disponible,
 * RabbitMQ arrancado y su API de gestión accesible con las credenciales del contenedor.
 */
class RabbitMqSmokeIT {

	@Test
	void managementApiRespondsWithTheRabbitMqVersion() throws Exception {
		HttpResponse<String> response = RabbitMqContainerSupport.managementGet("/api/overview");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"rabbitmq_version\":\"4.4");
	}

}
