package com.messaginglab.controller;

import com.messaginglab.api.model.ApiError;
import com.messaginglab.assistant.AssistantTimeoutException;
import com.messaginglab.assistant.AssistantUnavailableException;
import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerTimeoutException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.LabException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traduce los errores del laboratorio a {@link ApiError}, con mensajes en español para el usuario
 * y sin trazas ni detalles técnicos internos.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ValidationException.class)
	ResponseEntity<ApiError> validation(ValidationException exception) {
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION, exception);
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ApiError> notFound(NotFoundException exception) {
		return respond(HttpStatus.NOT_FOUND, ApiError.CodeEnum.NOT_FOUND, exception);
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ApiError> conflict(ConflictException exception) {
		return respond(HttpStatus.CONFLICT, ApiError.CodeEnum.CONFLICT, exception);
	}

	@ExceptionHandler(BrokerUnavailableException.class)
	ResponseEntity<ApiError> brokerUnavailable(BrokerUnavailableException exception) {
		return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.CodeEnum.BROKER_UNAVAILABLE, exception);
	}

	@ExceptionHandler(BrokerTimeoutException.class)
	ResponseEntity<ApiError> brokerTimeout(BrokerTimeoutException exception) {
		return respond(HttpStatus.GATEWAY_TIMEOUT, ApiError.CodeEnum.TIMEOUT, exception);
	}

	@ExceptionHandler(BrokerRejectedException.class)
	ResponseEntity<ApiError> brokerRejected(BrokerRejectedException exception) {
		return respond(HttpStatus.BAD_GATEWAY, ApiError.CodeEnum.BROKER_REJECTED, exception);
	}

	@ExceptionHandler(AssistantUnavailableException.class)
	ResponseEntity<ApiError> assistantUnavailable(AssistantUnavailableException exception) {
		return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.CodeEnum.ASSISTANT_UNAVAILABLE, exception);
	}

	@ExceptionHandler(AssistantTimeoutException.class)
	ResponseEntity<ApiError> assistantTimeout(AssistantTimeoutException exception) {
		return respond(HttpStatus.GATEWAY_TIMEOUT, ApiError.CodeEnum.TIMEOUT, exception);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION,
				"Falta el parámetro «" + exception.getParameterName() + "».");
	}

	/** Parámetro fuera del rango que declara el contrato (por ejemplo, un límite mayor que el máximo). */
	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiError> constraintViolation(ConstraintViolationException exception) {
		String path = exception.getConstraintViolations().iterator().next().getPropertyPath().toString();
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION,
				"El parámetro «" + path.substring(path.lastIndexOf('.') + 1) + "» no es válido.");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException exception) {
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION,
				"El valor del parámetro «" + exception.getName() + "» no es válido.");
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException exception) {
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION,
				"El cuerpo de la petición no es válido.");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
		String field = exception.getBindingResult().getFieldErrors().stream().findFirst().map(error -> error.getField())
			.orElse(null);
		String message = field == null ? "Los datos enviados no son válidos."
				: "El campo «" + field + "» no es válido.";
		return respond(HttpStatus.BAD_REQUEST, ApiError.CodeEnum.VALIDATION, message);
	}

	private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError.CodeEnum code, LabException exception) {
		return respond(status, code, exception.getMessage());
	}

	private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError.CodeEnum code, String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message));
	}

}
