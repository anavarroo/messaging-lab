package com.messaginglab.controller;

import com.messaginglab.api.AssistantApi;
import com.messaginglab.api.model.AssistantChatRequest;
import com.messaginglab.api.model.AssistantError;
import com.messaginglab.api.model.AssistantStatus;
import com.messaginglab.api.model.AssistantToken;
import com.messaginglab.assistant.AssistantService;
import com.messaginglab.assistant.Cancellation;
import com.messaginglab.assistant.LlmStatus;
import com.messaginglab.assistant.Message;
import com.messaginglab.exception.LabException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * El asistente de IA. Las comprobaciones (pregunta válida, asistente libre y modelo listo) se hacen antes de empezar a
 * responder, para devolver un error HTTP normal; la respuesta llega después por un flujo de eventos.
 */
@RestController
public class AssistantController implements AssistantApi {

	static final String TOKEN = "token";

	static final String DONE = "done";

	static final String ERROR = "error";

	private final AssistantService assistant;

	public AssistantController(AssistantService assistant) {
		this.assistant = assistant;
	}

	@Override
	public ResponseEntity<AssistantStatus> getAssistantStatus() {
		LlmStatus status = assistant.status();
		return ResponseEntity.ok(new AssistantStatus(AssistantStatus.StateEnum.valueOf(status.state().name())).message(status.message()));
	}

	@Override
	public ResponseEntity<SseEmitter> chatWithAssistant(AssistantChatRequest request) {
		List<Message> history = request.getHistory() == null ? List.of()
				: request.getHistory().stream().map(turn -> new Message(Message.Role.valueOf(turn.getRole().name()), turn.getText())).toList();
		AssistantService.Pending pending = assistant.begin(request.getQuestion(), history);
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream(pending, new SseEmitter(0L)));
	}

	/**
	 * Entrega la respuesta por el emisor desde un hilo virtual. Si el cliente se va, se cancela la generación. Separado
	 * para poder probarlo con un emisor falso.
	 */
	SseEmitter stream(AssistantService.Pending pending, SseEmitter emitter) {
		Cancellation cancel = new Cancellation();
		emitter.onCompletion(cancel::cancel);
		emitter.onTimeout(cancel::cancel);
		emitter.onError(error -> cancel.cancel());
		Thread.ofVirtual().name("assistant-stream").start(() -> {
			try {
				pending.run(token -> send(emitter, TOKEN, new AssistantToken(token), cancel), cancel);
				if (!cancel.isCancelled()) {
					send(emitter, DONE, java.util.Map.of(), cancel);
				}
			}
			catch (LabException exception) {
				// el modelo falla a mitad de la respuesta: lo recibido se conserva y se explica lo ocurrido
				send(emitter, ERROR, new AssistantError(exception.getMessage()), cancel);
			}
			catch (RuntimeException exception) {
				send(emitter, ERROR, new AssistantError("El asistente ha fallado. Inténtalo de nuevo en un momento."), cancel);
			}
			finally {
				emitter.complete();
			}
		});
		return emitter;
	}

	private static void send(SseEmitter emitter, String name, Object data, Cancellation cancel) {
		try {
			emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | RuntimeException exception) {
			// el navegador se ha ido (o cerró el panel): no hay nadie a quien contar nada y se detiene la generación
			cancel.cancel();
		}
	}

}
