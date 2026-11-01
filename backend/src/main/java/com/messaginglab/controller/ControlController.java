package com.messaginglab.controller;

import com.messaginglab.api.ControlApi;
import com.messaginglab.api.model.ControlActionRequest;
import com.messaginglab.api.model.ControlStatus;
import com.messaginglab.api.model.ControlledService;
import com.messaginglab.control.ServiceControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Parar y levantar RabbitMQ y el asistente de IA desde «Estado». */
@RestController
public class ControlController implements ControlApi {

	private final ServiceControl control;

	public ControlController(ServiceControl control) {
		this.control = control;
	}

	@Override
	public ResponseEntity<ControlStatus> getControlledServices() {
		ServiceControl.Status status = control.status();
		return ResponseEntity.ok(new ControlStatus(status.available(), status.services().stream().map(ControlController::toApi).toList())
			.reason(status.reason()));
	}

	@Override
	public ResponseEntity<ControlledService> actOnService(String service, ControlActionRequest request) {
		return ResponseEntity.ok(toApi(control.act(service, ServiceControl.Action.valueOf(request.getAction().name()))));
	}

	private static ControlledService toApi(ServiceControl.ServiceState state) {
		return new ControlledService(ControlledService.ServiceEnum.valueOf(state.service().name()),
				ControlledService.StateEnum.valueOf(state.state().name()))
			.message(state.message());
	}

}
