package com.messaginglab.assistant.ollama;

import com.messaginglab.assistant.AssistantService;
import com.messaginglab.assistant.ContextBuilder;
import com.messaginglab.assistant.KnowledgeBase;
import com.messaginglab.assistant.LlmClient;
import com.messaginglab.assistant.QuestionClassifier;
import com.messaginglab.assistant.SnapshotProvider;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta el núcleo del asistente, que no sabe nada de Ollama ni de RabbitMQ, con sus adaptadores. */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfiguration {

	@Bean
	LlmClient llmClient(AssistantProperties properties) {
		return new OllamaClient(properties);
	}

	@Bean
	AssistantService assistantService(SnapshotProvider snapshots, LlmClient llm) {
		return new AssistantService(snapshots, llm, new QuestionClassifier(), new ContextBuilder(new KnowledgeBase()),
				Clock.systemUTC());
	}

}
