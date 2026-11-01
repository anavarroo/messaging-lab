package com.messaginglab.controller;

import com.messaginglab.api.RabbitMqExchangesApi;
import com.messaginglab.api.model.CreateExchangeRequest;
import com.messaginglab.api.model.Exchange;
import com.messaginglab.api.model.ExchangeCreationResponse;
import com.messaginglab.api.model.ExchangeType;
import com.messaginglab.model.Creation;
import com.messaginglab.service.ExchangeService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implementa las operaciones sobre exchanges definidas en el contrato OpenAPI y traduce entre el
 * contrato y el modelo de dominio.
 */
@RestController
public class RabbitMqExchangesController implements RabbitMqExchangesApi {

	private final ExchangeService exchangeService;

	public RabbitMqExchangesController(ExchangeService exchangeService) {
		this.exchangeService = exchangeService;
	}

	@Override
	public ResponseEntity<List<Exchange>> listExchanges() {
		return ResponseEntity.ok(exchangeService.list().stream().map(RabbitMqExchangesController::toApi).toList());
	}

	@Override
	public ResponseEntity<ExchangeCreationResponse> createExchange(CreateExchangeRequest request) {
		Creation<com.messaginglab.model.Exchange> creation = exchangeService.create(request.getName(),
				com.messaginglab.model.ExchangeType.valueOf(request.getType().name()));
		ExchangeCreationResponse body = new ExchangeCreationResponse(toApi(creation.value()), creation.created());
		return ResponseEntity.status(creation.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
	}

	@Override
	public ResponseEntity<Void> deleteExchange(String name) {
		exchangeService.delete(name);
		return ResponseEntity.noContent().build();
	}

	private static Exchange toApi(com.messaginglab.model.Exchange exchange) {
		return new Exchange(exchange.name(), ExchangeType.valueOf(exchange.type().name()), exchange.predefined());
	}

}
