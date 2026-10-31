package com.messaginglab.assistant;

import java.util.List;
import java.util.function.Consumer;

/** El modelo de lenguaje. En el laboratorio es Ollama, pero el núcleo no lo sabe. */
public interface LlmClient {

	LlmStatus status();

	/**
	 * Genera la respuesta a la conversación, pasando cada trozo a {@code onToken}. Bloquea hasta terminar y debe
	 * detenerse pronto si {@code cancel} se activa. Si el modelo falla lanza una {@link AssistantUnavailableException}
	 * con el motivo en español.
	 */
	void chat(List<Message> messages, Consumer<String> onToken, Cancellation cancel);

}
