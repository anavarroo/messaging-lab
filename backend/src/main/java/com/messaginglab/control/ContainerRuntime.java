package com.messaginglab.control;

import java.util.List;
import java.util.Optional;

/**
 * Los contenedores de la máquina, tal como los deja ver el intermediario de Docker: listar, arrancar y parar, nada
 * más. Los fallos se lanzan como {@link com.messaginglab.exception.ControlUnavailableException}.
 */
public interface ContainerRuntime {

	/** El proyecto Compose al que pertenece el propio backend; vacío si no se ejecuta dentro de uno. */
	Optional<String> ownProject();

	/** Los contenedores de ese proyecto, parados incluidos. */
	List<Container> containers(String project);

	void start(String id);

	void stop(String id);

	/** @param service nombre del servicio en el Compose (etiqueta {@code com.docker.compose.service}) */
	record Container(String id, String service, boolean running) {

	}

}
