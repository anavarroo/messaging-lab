package com.messaginglab.gateway.docker;

import com.messaginglab.control.ServiceControl;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta el control de servicios con el intermediario de Docker, si está configurado (solo en el Compose). */
@Configuration
class ControlConfiguration {

	@Bean
	ServiceControl serviceControl(@Value("${messaginglab.control.docker-url:}") String dockerUrl) {
		if (dockerUrl.isBlank()) {
			return new ServiceControl(null);
		}
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3))
			.build();
		return new ServiceControl(new DockerProxyRuntime(dockerUrl, http, ControlConfiguration::hostname));
	}

	private static String hostname() {
		String fromEnvironment = System.getenv("HOSTNAME");
		if (fromEnvironment != null && !fromEnvironment.isBlank()) {
			return fromEnvironment;
		}
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException exception) {
			return null;
		}
	}

}
