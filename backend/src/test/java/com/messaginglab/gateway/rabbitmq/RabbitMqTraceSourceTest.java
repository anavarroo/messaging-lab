package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.messaginglab.flow.FlowSourceStatus;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** El arranque y la recepción real de eventos se prueban en {@code FlowIT}. */
class RabbitMqTraceSourceTest {

	private MockRestServiceServer server;

	private RabbitMqTraceSource source;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		RabbitMqProperties properties = new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "x", "/");
		ManagementApiClient management = new ManagementApiClient(builder, properties);
		source = new RabbitMqTraceSource(properties, management, null, Clock.systemUTC());
	}

	private void vhost(String body) {
		server.expect(requestTo("http://rabbit.test:15672/api/vhosts/%2F"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	@Test
	void isActiveWhenTheVirtualHostHasTracingOn() {
		vhost("{\"name\":\"/\",\"tracing\":true}");

		assertThat(source.status()).isEqualTo(new FlowSourceStatus(FlowSourceStatus.State.ACTIVE, null));
	}

	@Test
	void isDisabledWithTheCommandToEnableItWhenTracingIsOff() {
		vhost("{\"name\":\"/\",\"tracing\":false}");

		FlowSourceStatus status = source.status();

		assertThat(status.state()).isEqualTo(FlowSourceStatus.State.DISABLED);
		assertThat(status.hint()).contains("rabbitmqctl trace_on").contains("docker compose");
	}

	@Test
	void isDisabledWhenTheFieldIsMissing() {
		vhost("{\"name\":\"/\"}");

		assertThat(source.status().state()).isEqualTo(FlowSourceStatus.State.DISABLED);
	}

	@Test
	void isUnavailableWhenRabbitMqDoesNotAnswer() {
		server.expect(requestTo("http://rabbit.test:15672/api/vhosts/%2F")).andRespond(withServerError());

		FlowSourceStatus status = source.status();

		assertThat(status.state()).isEqualTo(FlowSourceStatus.State.UNAVAILABLE);
		assertThat(status.hint()).isEqualTo("RabbitMQ no está disponible.");
	}

	@Test
	void stoppingWithoutHavingStartedIsHarmless() {
		source.stop();
		source.stop();
	}

}
