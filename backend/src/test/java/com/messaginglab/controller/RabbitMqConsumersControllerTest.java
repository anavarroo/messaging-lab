package com.messaginglab.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import com.messaginglab.model.AckMode;
import com.messaginglab.model.ActionOutcome;
import com.messaginglab.model.ConsumerInfo;
import com.messaginglab.model.MessageAction;
import com.messaginglab.service.ConsumerSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RabbitMqConsumersController.class)
class RabbitMqConsumersControllerTest {

	private static final String CONSUMERS = "/api/rabbitmq/sessions/s1/consumers";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsumerSessionService sessions;

	@Test
	void startsAConsumerAndAnswers201() throws Exception {
		given(sessions.startConsumer("s1", "c1", "cola", AckMode.MANUAL))
			.willReturn(new ConsumerInfo("id-1", "c1", "cola", AckMode.MANUAL));

		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"queue\":\"cola\",\"ackMode\":\"MANUAL\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value("id-1"))
			.andExpect(jsonPath("$.name").value("c1"))
			.andExpect(jsonPath("$.queue").value("cola"))
			.andExpect(jsonPath("$.ackMode").value("MANUAL"));
	}

	@Test
	void startsAnAutomaticConsumer() throws Exception {
		given(sessions.startConsumer("s1", "c1", "cola", AckMode.AUTOMATIC))
			.willReturn(new ConsumerInfo("id-1", "c1", "cola", AckMode.AUTOMATIC));

		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"queue\":\"cola\",\"ackMode\":\"AUTOMATIC\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.ackMode").value("AUTOMATIC"));
	}

	@Test
	void aRepeatedNameAnswers409() throws Exception {
		given(sessions.startConsumer(any(), any(), any(), any()))
			.willThrow(new ConflictException("Ya hay un consumidor con el nombre «c1» en esta sesión."));

		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"queue\":\"cola\",\"ackMode\":\"MANUAL\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value("Ya hay un consumidor con el nombre «c1» en esta sesión."));
	}

	@Test
	void aMissingSessionOrQueueAnswers404() throws Exception {
		given(sessions.startConsumer(any(), any(), any(), any()))
			.willThrow(new NotFoundException("La sesión no existe o ya ha terminado."));

		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"queue\":\"cola\",\"ackMode\":\"MANUAL\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void aValidationErrorAnswers400() throws Exception {
		given(sessions.startConsumer(any(), any(), any(), any()))
			.willThrow(new ValidationException("El nombre del consumidor no puede estar vacío."));

		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\",\"queue\":\"cola\",\"ackMode\":\"MANUAL\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("El nombre del consumidor no puede estar vacío."));
	}

	@Test
	void anUnknownAckModeIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"queue\":\"cola\",\"ackMode\":\"SEMI\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));

		verifyNoInteractions(sessions);
	}

	@Test
	void aMissingQueueFieldIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post(CONSUMERS).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"c1\",\"ackMode\":\"MANUAL\"}")).andExpect(status().isBadRequest());

		verifyNoInteractions(sessions);
	}

	@Test
	void stopsAConsumerAndAnswers204() throws Exception {
		mockMvc.perform(delete(CONSUMERS + "/id-1")).andExpect(status().isNoContent());

		verify(sessions).stopConsumer("s1", "id-1");
	}

	@Test
	void stoppingAMissingConsumerAnswers404() throws Exception {
		Mockito.doThrow(new NotFoundException("El consumidor no existe en esta sesión: puede que ya haya terminado."))
			.when(sessions)
			.stopConsumer("s1", "x");

		mockMvc.perform(delete(CONSUMERS + "/x"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void confirmsAMessageAndReportsTheOutcome() throws Exception {
		given(sessions.resolve("s1", "id-1", 7L, MessageAction.ACK)).willReturn(ActionOutcome.CONFIRMED);

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACK\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("CONFIRMED"));
	}

	@Test
	void requeuesAMessageAndReportsTheOutcome() throws Exception {
		given(sessions.resolve("s1", "id-1", 7L, MessageAction.REQUEUE)).willReturn(ActionOutcome.REQUEUED);

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"REQUEUE\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("REQUEUED"));
	}

	@Test
	void rejectingReportsWhetherItWentToTheDeadLetterQueueOrWasDiscarded() throws Exception {
		given(sessions.resolve("s1", "id-1", 7L, MessageAction.REJECT)).willReturn(ActionOutcome.DEAD_LETTERED);
		given(sessions.resolve("s1", "id-2", 8L, MessageAction.REJECT)).willReturn(ActionOutcome.DISCARDED);

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"REJECT\"}"))
			.andExpect(jsonPath("$.outcome").value("DEAD_LETTERED"));
		mockMvc.perform(post(CONSUMERS + "/id-2/messages/8").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"REJECT\"}"))
			.andExpect(jsonPath("$.outcome").value("DISCARDED"));
	}

	@Test
	void resolvingTwiceAnswers409() throws Exception {
		given(sessions.resolve(any(), any(), Mockito.anyLong(), any()))
			.willThrow(new ConflictException("Este mensaje ya se ha resuelto."));

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACK\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value("Este mensaje ya se ha resuelto."));
	}

	@Test
	void aMissingConsumerOrMessageAnswers404() throws Exception {
		given(sessions.resolve(any(), any(), Mockito.anyLong(), any()))
			.willThrow(new NotFoundException("El mensaje no existe en este consumidor."));

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACK\"}")).andExpect(status().isNotFound());
	}

	@Test
	void anUnknownActionOrANonNumericTagIsRejectedBeforeReachingTheService() throws Exception {
		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"BORRAR\"}")).andExpect(status().isBadRequest());
		mockMvc.perform(post(CONSUMERS + "/id-1/messages/abc").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACK\"}")).andExpect(status().isBadRequest());

		verifyNoInteractions(sessions);
	}

	@Test
	void anUnavailableBrokerAnswers503() throws Exception {
		given(sessions.resolve(any(), any(), Mockito.anyLong(), any()))
			.willThrow(new BrokerUnavailableException("RabbitMQ no está disponible."));

		mockMvc.perform(post(CONSUMERS + "/id-1/messages/7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACK\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("BROKER_UNAVAILABLE"));
	}

}
