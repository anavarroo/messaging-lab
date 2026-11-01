package com.messaginglab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.messaginglab.control.ContainerRuntime.Container;
import com.messaginglab.control.ServiceControl.Action;
import com.messaginglab.control.ServiceControl.Service;
import com.messaginglab.control.ServiceControl.State;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.ControlUnavailableException;
import com.messaginglab.exception.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceControlTest {

	private final FakeRuntime docker = new FakeRuntime();

	private final ServiceControl control = new ServiceControl(docker);

	@Test
	void reportsEachServiceOfItsOwnProject() {
		docker.add("lab", "rabbitmq", true).add("lab", "ollama", true).add("lab", "assistant", false);

		ServiceControl.Status status = control.status();

		assertThat(status.available()).isTrue();
		assertThat(status.services()).extracting(ServiceControl.ServiceState::service, ServiceControl.ServiceState::state)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(Service.RABBITMQ, State.RUNNING),
					org.assertj.core.groups.Tuple.tuple(Service.ASSISTANT, State.STOPPED));
	}

	@Test
	void theAssistantIsMissingWhenItWasNotCreated() {
		docker.add("lab", "rabbitmq", true);

		ServiceControl.ServiceState assistant = control.status().services().get(1);

		assertThat(assistant.state()).isEqualTo(State.MISSING);
		assertThat(assistant.message()).contains("OLLAMA_REPLICAS=1");
		assertThatThrownBy(() -> control.act("assistant", Action.START)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("OLLAMA_REPLICAS=0");
	}

	@Test
	void stopsRabbitMqOnly() {
		docker.add("lab", "rabbitmq", true).add("lab", "backend", true).add("lab", "ollama", true);

		assertThat(control.act("rabbitmq", Action.STOP).state()).isEqualTo(State.STOPPED);

		assertThat(docker.calls).containsExactly("stop lab-rabbitmq");
	}

	@Test
	void theAssistantStartsAfterItsOllamaAndStopsBeforeIt() {
		docker.add("lab", "assistant", false).add("lab", "ollama", false);

		assertThat(control.act("assistant", Action.START).state()).isEqualTo(State.RUNNING);
		assertThat(control.act("ASSISTANT", Action.STOP).state()).isEqualTo(State.STOPPED);

		assertThat(docker.calls).containsExactly("start lab-ollama", "start lab-assistant", "stop lab-assistant", "stop lab-ollama");
	}

	@Test
	void withOllamaOutsideDockerTheAssistantGoesAlone() {
		docker.add("lab", "assistant", true);

		control.act("assistant", Action.STOP);

		assertThat(docker.calls).containsExactly("stop lab-assistant");
	}

	@Test
	void doesNothingToWhatIsAlreadyInThatState() {
		docker.add("lab", "rabbitmq", true);

		assertThat(control.act("rabbitmq", Action.START).state()).isEqualTo(State.RUNNING);

		assertThat(docker.calls).isEmpty();
	}

	@Test
	void neverActsOnAnyOtherServiceOrProject() {
		docker.add("lab", "rabbitmq", true).add("otro", "rabbitmq", true);

		for (String other : new String[] { "backend", "frontend", "ollama", "docker-proxy", "otro-rabbitmq", "", null }) {
			assertThatThrownBy(() -> control.act(other, Action.STOP)).as(String.valueOf(other))
				.isInstanceOf(NotFoundException.class)
				.hasMessage("Solo se pueden parar y levantar RabbitMQ y el asistente de IA.");
		}
		control.act("rabbitmq", Action.STOP);

		assertThat(docker.calls).containsExactly("stop lab-rabbitmq");
		assertThat(docker.running("otro-rabbitmq")).isTrue();
	}

	@Test
	void isUnavailableWithoutTheDockerIntermediary() {
		ServiceControl withoutDocker = new ServiceControl(null);

		assertThat(withoutDocker.status()).isEqualTo(new ServiceControl.Status(false, ServiceControl.NO_COMPOSE, List.of()));
		assertThatThrownBy(() -> withoutDocker.act("rabbitmq", Action.STOP)).isInstanceOf(ControlUnavailableException.class)
			.hasMessageContaining("docker compose -f infra/docker-compose.yml up -d --build");
	}

	@Test
	void isUnavailableWhenTheBackendIsNotInACompose() {
		docker.project = null;

		assertThat(control.status().available()).isFalse();
		assertThat(control.status().reason()).isEqualTo(ServiceControl.NO_COMPOSE);
	}

	@Test
	void aDockerFailureIsExplainedInTheStatus() {
		docker.failure = new ControlUnavailableException("Docker no responde.");

		assertThat(control.status()).isEqualTo(new ServiceControl.Status(false, "Docker no responde.", List.of()));
	}

	/** Un Docker en memoria con contenedores de varios proyectos; apunta lo que se le pide. */
	private static final class FakeRuntime implements ContainerRuntime {

		private final Map<String, Container> containers = new LinkedHashMap<>();

		private final Map<String, String> projects = new LinkedHashMap<>();

		private final List<String> calls = new ArrayList<>();

		private String project = "lab";

		private ControlUnavailableException failure;

		FakeRuntime add(String project, String service, boolean running) {
			String id = project + "-" + service;
			containers.put(id, new Container(id, service, running));
			projects.put(id, project);
			return this;
		}

		boolean running(String id) {
			return containers.get(id).running();
		}

		@Override
		public Optional<String> ownProject() {
			if (failure != null) {
				throw failure;
			}
			return Optional.ofNullable(project);
		}

		@Override
		public List<Container> containers(String project) {
			return containers.values().stream().filter(container -> projects.get(container.id()).equals(project)).toList();
		}

		@Override
		public void start(String id) {
			calls.add("start " + id);
			containers.put(id, new Container(id, containers.get(id).service(), true));
		}

		@Override
		public void stop(String id) {
			calls.add("stop " + id);
			containers.put(id, new Container(id, containers.get(id).service(), false));
		}

	}

}
