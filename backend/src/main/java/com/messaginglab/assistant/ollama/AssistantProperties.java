package com.messaginglab.assistant.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del asistente (variables ASSISTANT_*, ver application.properties).
 * @param url dirección de Ollama
 * @param model modelo que se usa; la primera vez se descarga
 * @param contextTokens tamaño del contexto del modelo (cuanto menor, menos memoria)
 * @param maxResponseTokens longitud máxima de una respuesta
 */
@ConfigurationProperties("messaginglab.assistant")
public record AssistantProperties(String url, String model, int contextTokens, int maxResponseTokens, double temperature) {

}
