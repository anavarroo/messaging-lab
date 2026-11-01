package com.messaginglab.service;

import com.messaginglab.model.ApplicationStatus;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Construye el estado del backend a partir de los metadatos de compilación,
 * fuente única del nombre y la versión.
 */
@Service
public class StatusService {

	private final BuildProperties buildProperties;

	public StatusService(BuildProperties buildProperties) {
		this.buildProperties = buildProperties;
	}

	public ApplicationStatus getStatus() {
		return new ApplicationStatus(ApplicationStatus.State.AVAILABLE, buildProperties.getName(),
				buildProperties.getVersion());
	}

}
