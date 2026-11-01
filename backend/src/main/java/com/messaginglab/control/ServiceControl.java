package com.messaginglab.control;

import com.messaginglab.control.ContainerRuntime.Container;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.ControlUnavailableException;
import com.messaginglab.exception.NotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Para y levanta RabbitMQ y el asistente de IA. Es la lista blanca: solo actúa sobre esos servicios y solo sobre los
 * contenedores del mismo proyecto Compose que el backend, aunque el intermediario de Docker permitiese más.
 */
public final class ServiceControl {

	public static final String NO_COMPOSE = "Solo se puede parar y levantar desde aquí cuando el laboratorio se ejecuta "
			+ "con su Docker Compose («docker compose -f infra/docker-compose.yml up -d --build»). Si lo has arrancado "
			+ "por componentes, para y levanta cada servicio desde la terminal.";

	/**
	 * Los servicios controlables. {@code containers} va en el orden de arranque (se paran al revés); el primero que
	 * no es opcional es el que tiene que existir.
	 */
	public enum Service {

		RABBITMQ("rabbitmq", "rabbitmq", List.of("rabbitmq"),
				"RabbitMQ no existe en este proyecto. Créalo con «docker compose -f infra/docker-compose.yml up -d rabbitmq»."),
		/** Con su Ollama, que se para con él para liberar la memoria del modelo. */
		ASSISTANT("assistant", "assistant", List.of("ollama", "assistant"),
				"El asistente no está creado: el laboratorio se ha levantado con OLLAMA_REPLICAS=0. Para usarlo, "
						+ "«OLLAMA_REPLICAS=1 docker compose -f infra/docker-compose.yml up -d».");

		private final String id;

		private final String main;

		private final List<String> containers;

		private final String missing;

		Service(String id, String main, List<String> containers, String missing) {
			this.id = id;
			this.main = main;
			this.containers = containers;
			this.missing = missing;
		}

		static Service of(String id) {
			return Arrays.stream(values())
				.filter(service -> service.id.equals(id == null ? null : id.toLowerCase(Locale.ROOT)))
				.findFirst()
				.orElseThrow(() -> new NotFoundException("Solo se pueden parar y levantar RabbitMQ y el asistente de IA."));
		}

	}

	public enum State {

		RUNNING, STOPPED, MISSING

	}

	public enum Action {

		START, STOP

	}

	/** @param message explicación cuando no existe; {@code null} si existe */
	public record ServiceState(Service service, State state, String message) {

	}

	/** @param reason por qué no se puede usar; {@code null} si se puede */
	public record Status(boolean available, String reason, List<ServiceState> services) {

	}

	private final ContainerRuntime runtime;

	/** @param runtime el intermediario de Docker; {@code null} si no está configurado (arranque sin Compose) */
	public ServiceControl(ContainerRuntime runtime) {
		this.runtime = runtime;
	}

	public Status status() {
		try {
			String project = project();
			List<Container> containers = runtime.containers(project);
			return new Status(true, null, Arrays.stream(Service.values()).map(service -> state(service, containers)).toList());
		}
		catch (ControlUnavailableException exception) {
			return new Status(false, exception.getMessage(), List.of());
		}
	}

	/** Arranca o para el servicio y devuelve su nuevo estado. Responde cuando Docker ha terminado. */
	public ServiceState act(String serviceId, Action action) {
		Service service = Service.of(serviceId);
		String project = project();
		List<Container> containers = runtime.containers(project);
		if (state(service, containers).state() == State.MISSING) {
			throw new ConflictException(service.missing);
		}
		List<Container> own = ordered(service, containers);
		if (action == Action.START) {
			own.stream().filter(container -> !container.running()).forEach(container -> runtime.start(container.id()));
		}
		else {
			own.reversed().stream().filter(Container::running).forEach(container -> runtime.stop(container.id()));
		}
		return state(service, runtime.containers(project));
	}

	private String project() {
		if (runtime == null) {
			throw new ControlUnavailableException(NO_COMPOSE);
		}
		Optional<String> project = runtime.ownProject();
		return project.orElseThrow(() -> new ControlUnavailableException(NO_COMPOSE));
	}

	private static ServiceState state(Service service, List<Container> containers) {
		List<Container> own = ordered(service, containers);
		if (own.stream().noneMatch(container -> container.service().equals(service.main))) {
			return new ServiceState(service, State.MISSING, service.missing);
		}
		return new ServiceState(service, own.stream().allMatch(Container::running) ? State.RUNNING : State.STOPPED, null);
	}

	/** Los contenedores del servicio, en orden de arranque; con Ollama fuera de Docker, el asistente va solo. */
	private static List<Container> ordered(Service service, List<Container> containers) {
		return service.containers.stream()
			.flatMap(name -> containers.stream().filter(container -> container.service().equals(name)))
			.toList();
	}

}
