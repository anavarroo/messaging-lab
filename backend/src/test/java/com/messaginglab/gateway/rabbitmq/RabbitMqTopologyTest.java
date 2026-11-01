package com.messaginglab.gateway.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.messaginglab.exception.BrokerRejectedException;
import com.messaginglab.exception.BrokerUnavailableException;
import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.model.Binding;
import com.messaginglab.model.BrokerStatus;
import com.messaginglab.model.Creation;
import com.messaginglab.model.DeathInfo;
import com.messaginglab.model.Exchange;
import com.messaginglab.model.ExchangeType;
import com.messaginglab.model.Queue;
import com.messaginglab.model.QueueInspection;
import com.messaginglab.model.QueueMessage;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Pruebas del adaptador con respuestas simuladas de la API de gestión; sin RabbitMQ real. */
class RabbitMqTopologyTest {

	private static final String BASE = "http://rabbit.test:15672/api";

	private MockRestServiceServer server;

	private RabbitMqTopology topology;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		topology = new RabbitMqTopology(new ManagementApiClient(builder,
				new RabbitMqProperties("rabbit.test", 5672, 15672, "lab", "secreto", "/")));
	}

	@Test
	void statusReturnsTheRabbitMqVersion() {
		server.expect(requestTo(BASE + "/overview"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"rabbitmq_version\":\"4.4.0\",\"management_version\":\"4.4.0\"}",
					MediaType.APPLICATION_JSON));

		assertThat(topology.status()).isEqualTo(new BrokerStatus("4.4.0"));
		server.verify();
	}

	@Test
	void statusFailsAsUnavailableWhenRabbitMqCannotBeReached() {
		server.expect(requestTo(BASE + "/overview")).andRespond(request -> {
			throw new ConnectException("Connection refused");
		});

		assertThatThrownBy(topology::status).isInstanceOf(BrokerUnavailableException.class)
			.hasMessage("RabbitMQ no está disponible.");
	}

	@Test
	void statusFailsAsRejectedWhenTheCredentialsAreWrong() {
		server.expect(requestTo(BASE + "/overview")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(topology::status).isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ ha rechazado las credenciales configuradas.");
	}

	@Test
	void statusFailsWhenTheResponseHasNoVersion() {
		server.expect(requestTo(BASE + "/overview")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(topology::status).isInstanceOf(BrokerRejectedException.class)
			.hasMessage("RabbitMQ ha devuelto una respuesta que no se puede interpretar.");
	}

	@Test
	void listExchangesKeepsTheSupportedTypesAndMarksThePredefinedOnes() {
		server.expect(requestTo(BASE + "/exchanges/%2F"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					[{"name":"","type":"direct","durable":true},
					 {"name":"amq.direct","type":"direct","durable":true},
					 {"name":"amq.fanout","type":"fanout","durable":true},
					 {"name":"amq.headers","type":"headers","durable":true},
					 {"name":"amq.topic","type":"topic","durable":true},
					 {"name":"pedidos","type":"topic","durable":true},
					 {"name":"hash","type":"x-consistent-hash","durable":true}]
					""", MediaType.APPLICATION_JSON));

		List<Exchange> exchanges = topology.listExchanges();

		assertThat(exchanges).containsExactly(new Exchange("", ExchangeType.DIRECT, true),
				new Exchange("amq.direct", ExchangeType.DIRECT, true),
				new Exchange("amq.fanout", ExchangeType.FANOUT, true),
				new Exchange("amq.topic", ExchangeType.TOPIC, true),
				new Exchange("pedidos", ExchangeType.TOPIC, false));
	}

	@Test
	void createExchangeSendsADurableDefinitionAndReportsItWasCreated() {
		server.expect(requestTo(BASE + "/exchanges/%2F/pedidos"))
			.andExpect(method(HttpMethod.PUT))
			.andExpect(content().json("{\"type\":\"topic\",\"durable\":true,\"auto_delete\":false,\"internal\":false}"))
			.andRespond(withStatus(HttpStatus.CREATED));

		Creation<Exchange> creation = topology.createExchange("pedidos", ExchangeType.TOPIC);

		assertThat(creation).isEqualTo(new Creation<>(new Exchange("pedidos", ExchangeType.TOPIC, false), true));
		server.verify();
	}

	@Test
	void createExchangeReportsAnIdenticalExistingOneAsNotCreated() {
		server.expect(requestTo(BASE + "/exchanges/%2F/pedidos")).andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThat(topology.createExchange("pedidos", ExchangeType.TOPIC).created()).isFalse();
	}

	@Test
	void createExchangeWithAnotherTypeIsAConflict() {
		server.expect(requestTo(BASE + "/exchanges/%2F/pedidos"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"reason\":\"inequivalent arg 'type' for exchange 'pedidos' in vhost '/'\"}"));

		assertThatThrownBy(() -> topology.createExchange("pedidos", ExchangeType.DIRECT))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("difiere en el tipo");
	}

	@Test
	void exchangeNamesWithSpecialCharactersAreEncoded() {
		server.expect(requestTo(BASE + "/exchanges/%2F/a%2Fb%25c%20%C3%B1"))
			.andExpect(method(HttpMethod.PUT))
			.andRespond(withStatus(HttpStatus.CREATED));
		server.expect(requestTo(BASE + "/exchanges/%2F/a%2Fb%25c%20%C3%B1"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.createExchange("a/b%c ñ", ExchangeType.DIRECT);
		topology.deleteExchange("a/b%c ñ");

		server.verify();
	}

	@Test
	void deleteExchangeSendsADeleteRequest() {
		server.expect(requestTo(BASE + "/exchanges/%2F/pedidos"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.deleteExchange("pedidos");

		server.verify();
	}

	@Test
	void deletingAMissingExchangeNamesIt() {
		server.expect(requestTo(BASE + "/exchanges/%2F/pedidos")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> topology.deleteExchange("pedidos")).isInstanceOf(NotFoundException.class)
			.hasMessage("El exchange «pedidos» no existe.");
	}

	private static final String QUEUES_WITH_COUNTERS = BASE + "/queues/%2F?disable_stats=true&enable_queue_totals=true";

	@Test
	void listQueuesReadsTheCountersAndTheDeadLetterQueueFromTheArguments() {
		server.expect(requestTo(QUEUES_WITH_COUNTERS))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					[{"name":"pedidos","durable":true,"messages":5,"messages_ready":3,"messages_unacknowledged":2,
					  "arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"","x-dead-letter-routing-key":"fallidos"}},
					 {"name":"fallidos","durable":true,"messages":0,"messages_ready":0,"messages_unacknowledged":0,
					  "arguments":{"x-queue-type":"classic"}},
					 {"name":"otra","arguments":{"x-dead-letter-exchange":"un-exchange","x-dead-letter-routing-key":"k"}}]
					""", MediaType.APPLICATION_JSON));

		assertThat(topology.listQueues()).containsExactly(new Queue("pedidos", "fallidos", 3, 2, 5),
				new Queue("fallidos", null, 0, 0, 0), new Queue("otra", null, 0, 0, 0));
		server.verify();
	}

	@Test
	void createQueueSendsADurableClassicQueueWithoutDeadLetterQueue() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos"))
			.andExpect(method(HttpMethod.PUT))
			.andExpect(content().json("""
					{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"classic"}}
					""", true))
			.andRespond(withStatus(HttpStatus.CREATED));

		Creation<Queue> creation = topology.createQueue("pedidos", null);

		assertThat(creation).isEqualTo(new Creation<>(new Queue("pedidos", null, 0, 0, 0), true));
		server.verify();
	}

	@Test
	void createQueueWithDeadLetterQueueRoutesRejectedMessagesThroughTheDefaultExchange() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos"))
			.andExpect(method(HttpMethod.PUT))
			.andExpect(content().json("""
					{"durable":true,"auto_delete":false,
					 "arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"","x-dead-letter-routing-key":"fallidos"}}
					""", true))
			.andRespond(withStatus(HttpStatus.CREATED));

		Creation<Queue> creation = topology.createQueue("pedidos", "fallidos");

		assertThat(creation.created()).isTrue();
		assertThat(creation.value().deadLetterQueue()).isEqualTo("fallidos");
	}

	@Test
	void createQueueReportsAnIdenticalExistingOneWithItsCurrentCounters() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos")).andRespond(withStatus(HttpStatus.NO_CONTENT));
		server.expect(requestTo(BASE + "/queues/%2F/pedidos?disable_stats=true&enable_queue_totals=true"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					{"name":"pedidos","messages":4,"messages_ready":3,"messages_unacknowledged":1,
					 "arguments":{"x-queue-type":"classic","x-dead-letter-exchange":"","x-dead-letter-routing-key":"fallidos"}}
					""", MediaType.APPLICATION_JSON));

		Creation<Queue> creation = topology.createQueue("pedidos", "fallidos");

		assertThat(creation).isEqualTo(new Creation<>(new Queue("pedidos", "fallidos", 3, 1, 4), false));
		server.verify();
	}

	@Test
	void createQueueWithAnotherDeadLetterQueueIsAConflict() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"reason\":\"inequivalent arg 'x-dead-letter-routing-key' for queue 'pedidos' in vhost '/'\"}"));

		assertThatThrownBy(() -> topology.createQueue("pedidos", "otra")).isInstanceOf(ConflictException.class)
			.hasMessageContaining("difiere en la dead-letter queue");
	}

	@Test
	void queueNamesWithSpecialCharactersAreEncoded() {
		server.expect(requestTo(BASE + "/queues/%2F/a%2Fb%25c%20%C3%B1"))
			.andExpect(method(HttpMethod.PUT))
			.andRespond(withStatus(HttpStatus.CREATED));
		server.expect(requestTo(BASE + "/queues/%2F/a%2Fb%25c%20%C3%B1"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));
		server.expect(requestTo(BASE + "/queues/%2F/a%2Fb%25c%20%C3%B1/contents"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.createQueue("a/b%c ñ", null);
		topology.deleteQueue("a/b%c ñ");
		topology.purgeQueue("a/b%c ñ");

		server.verify();
	}

	@Test
	void deletingAMissingQueueNamesIt() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> topology.deleteQueue("pedidos")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «pedidos» no existe.");
	}

	@Test
	void purgeQueueDeletesTheContentsOnly() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos/contents"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.purgeQueue("pedidos");

		server.verify();
	}

	@Test
	void purgingAMissingQueueNamesIt() {
		server.expect(requestTo(BASE + "/queues/%2F/pedidos/contents")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> topology.purgeQueue("pedidos")).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «pedidos» no existe.");
	}

	private static final String PAIR = BASE + "/bindings/%2F/e/temas/q/cola1";

	@Test
	void listBindingsSkipsTheImplicitOnesAndTheOnesNotTowardsQueues() {
		server.expect(requestTo(BASE + "/bindings/%2F"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					[{"source":"","destination":"cola1","destination_type":"queue","routing_key":"cola1"},
					 {"source":"temas","destination":"cola1","destination_type":"queue","routing_key":"a.*"},
					 {"source":"difusion","destination":"cola2","destination_type":"queue","routing_key":""},
					 {"source":"temas","destination":"otro","destination_type":"exchange","routing_key":"x"}]
					""", MediaType.APPLICATION_JSON));

		assertThat(topology.listBindings(null, null)).containsExactly(new Binding("temas", "cola1", "a.*"),
				new Binding("difusion", "cola2", ""));
		server.verify();
	}

	@Test
	void listBindingsFiltersByExchangeAndQueue() {
		String body = """
				[{"source":"temas","destination":"cola1","destination_type":"queue","routing_key":"a"},
				 {"source":"temas","destination":"cola2","destination_type":"queue","routing_key":"b"},
				 {"source":"otro","destination":"cola1","destination_type":"queue","routing_key":"c"}]
				""";
		server.expect(requestTo(BASE + "/bindings/%2F")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/bindings/%2F")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/bindings/%2F")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(topology.listBindings("temas", null)).hasSize(2);
		assertThat(topology.listBindings(null, "cola1")).hasSize(2);
		assertThat(topology.listBindings("temas", "cola1")).containsExactly(new Binding("temas", "cola1", "a"));
	}

	@Test
	void createBindingChecksTheExistingOnesOfThePairAndThenPostsTheNewOne() {
		server.expect(requestTo(PAIR))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					[{"source":"temas","destination":"cola1","destination_type":"queue","routing_key":"otra"}]
					""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAIR))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("{\"routing_key\":\"a.*\",\"arguments\":{}}", true))
			.andRespond(withStatus(HttpStatus.CREATED));

		Creation<Binding> creation = topology.createBinding("temas", "cola1", "a.*");

		assertThat(creation).isEqualTo(new Creation<>(new Binding("temas", "cola1", "a.*"), true));
		server.verify();
	}

	@Test
	void createBindingDoesNotPostWhenAnIdenticalOneExists() {
		server.expect(requestTo(PAIR))
			.andRespond(withSuccess("""
					[{"source":"temas","destination":"cola1","destination_type":"queue","routing_key":"a.*"}]
					""", MediaType.APPLICATION_JSON));

		Creation<Binding> creation = topology.createBinding("temas", "cola1", "a.*");

		assertThat(creation.created()).isFalse();
		server.verify();
	}

	@Test
	void createBindingWithAnEmptyRoutingKeySendsItEmpty() {
		server.expect(requestTo(PAIR)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
		server.expect(requestTo(PAIR))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("{\"routing_key\":\"\",\"arguments\":{}}", true))
			.andRespond(withStatus(HttpStatus.CREATED));

		assertThat(topology.createBinding("temas", "cola1", "").created()).isTrue();
		server.verify();
	}

	@Test
	void deleteBindingUsesTheEncodedKeyOfTheBinding() {
		server.expect(requestTo(PAIR + "/a.%252A"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));
		server.expect(requestTo(PAIR + "/a.%2523"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.deleteBinding("temas", "cola1", "a.*");
		topology.deleteBinding("temas", "cola1", "a.#");

		server.verify();
	}

	@Test
	void deleteBindingWithAnEmptyRoutingKeyUsesTheTilde() {
		server.expect(requestTo(PAIR + "/~"))
			.andExpect(method(HttpMethod.DELETE))
			.andRespond(withStatus(HttpStatus.NO_CONTENT));

		topology.deleteBinding("temas", "cola1", "");

		server.verify();
	}

	@Test
	void deletingAMissingBindingNamesIt() {
		server.expect(requestTo(PAIR + "/a")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> topology.deleteBinding("temas", "cola1", "a")).isInstanceOf(NotFoundException.class)
			.hasMessage("El binding indicado no existe.");
	}

	@Test
	void propertiesKeyMatchesWhatRabbitMqReportsForSpecialCharacters() {
		assertThat(RabbitMqTopology.propertiesKey("")).isEqualTo("~");
		assertThat(RabbitMqTopology.propertiesKey("a.#")).isEqualTo("a.%23");
		assertThat(RabbitMqTopology.propertiesKey("a.*")).isEqualTo("a.%2A");
		assertThat(RabbitMqTopology.propertiesKey("a b")).isEqualTo("a+b");
		assertThat(RabbitMqTopology.propertiesKey("a+b")).isEqualTo("a%2Bb");
		assertThat(RabbitMqTopology.propertiesKey("a~b")).isEqualTo("a%7Eb");
		assertThat(RabbitMqTopology.propertiesKey("~")).isEqualTo("%7E");
		assertThat(RabbitMqTopology.propertiesKey("x/y%z ñ")).isEqualTo("x%2Fy%25z+%C3%B1");
	}

	// Las respuestas de las pruebas de inspección son capturas reales de RabbitMQ 4.4 (plan §2).
	private static final String GET_ORIG = BASE + "/queues/%2F/orig/get";

	private static final String GET_BODY = "{\"count\":50,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}";

	@Test
	void inspectQueueAsksForTheFirst50MessagesWithoutRemovingThem() {
		server.expect(requestTo(GET_ORIG))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json(GET_BODY, true))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		QueueInspection inspection = topology.inspectQueue("orig", 50);

		assertThat(inspection).isEqualTo(new QueueInspection(List.of(), 0, false));
		server.verify();
	}

	@Test
	void inspectQueueMapsANormalMessageAndComputesTheTotalFromTheRemainingCount() {
		server.expect(requestTo(GET_ORIG))
			.andRespond(withSuccess("""
					[{"payload_bytes":8,"redelivered":false,"exchange":"ex","routing_key":"rk1","message_count":2,
					  "properties":[],"payload":"msg 1 ñ","payload_encoding":"string"},
					 {"payload_bytes":8,"redelivered":false,"exchange":"ex","routing_key":"rk1","message_count":1,
					  "properties":[],"payload":"msg 2 ñ","payload_encoding":"string"}]
					""", MediaType.APPLICATION_JSON));

		QueueInspection inspection = topology.inspectQueue("orig", 2);

		assertThat(inspection.messages()).containsExactly(new QueueMessage("msg 1 ñ", "ex", "rk1", false, null),
				new QueueMessage("msg 2 ñ", "ex", "rk1", false, null));
		assertThat(inspection.total()).isEqualTo(3);
		assertThat(inspection.truncated()).isTrue();
	}

	@Test
	void inspectQueueMarksARedeliveredMessage() {
		server.expect(requestTo(GET_ORIG))
			.andRespond(withSuccess("""
					[{"payload_bytes":8,"redelivered":true,"exchange":"ex","routing_key":"rk1","message_count":0,
					  "properties":[],"payload":"msg 1 ñ","payload_encoding":"string"}]
					""", MediaType.APPLICATION_JSON));

		QueueInspection inspection = topology.inspectQueue("orig", 50);

		assertThat(inspection.messages().get(0).redelivered()).isTrue();
		assertThat(inspection.total()).isEqualTo(1);
		assertThat(inspection.truncated()).isFalse();
	}

	@Test
	void inspectQueueReadsTheOriginOfADeadLetterQueueMessageFromTheDeathHeaders() {
		server.expect(requestTo(BASE + "/queues/%2F/dead/get"))
			.andRespond(withSuccess("""
					[{"payload_bytes":8,"redelivered":true,"exchange":"","routing_key":"dead","message_count":0,
					  "properties":{"headers":{
					    "x-death":[{"count":1,"exchange":"ex","queue":"orig","reason":"rejected","routing-keys":["rk1"],"time":1793377788}],
					    "x-first-death-exchange":"ex","x-first-death-queue":"orig","x-first-death-reason":"rejected",
					    "x-last-death-exchange":"ex","x-last-death-queue":"orig","x-last-death-reason":"rejected"}},
					  "payload":"msg 1 ñ","payload_encoding":"string"}]
					""", MediaType.APPLICATION_JSON));

		QueueMessage message = topology.inspectQueue("dead", 50).messages().get(0);

		assertThat(message).isEqualTo(
				new QueueMessage("msg 1 ñ", "ex", "rk1", true, new DeathInfo("orig", "rejected")));
	}

	@Test
	void inspectQueueUsesTheOldestDeathWhenTheMessageDiedSeveralTimes() {
		server.expect(requestTo(BASE + "/queues/%2F/dead/get"))
			.andRespond(withSuccess("""
					[{"redelivered":false,"exchange":"","routing_key":"dead","message_count":0,
					  "properties":{"headers":{
					    "x-death":[{"count":1,"exchange":"","queue":"segunda","reason":"rejected","routing-keys":["segunda"]},
					               {"count":1,"exchange":"ex","queue":"primera","reason":"rejected","routing-keys":["rk1"]}],
					    "x-first-death-queue":"primera","x-first-death-reason":"rejected"}},
					  "payload":"m","payload_encoding":"string"}]
					""", MediaType.APPLICATION_JSON));

		QueueMessage message = topology.inspectQueue("dead", 50).messages().get(0);

		assertThat(message.exchange()).isEqualTo("ex");
		assertThat(message.routingKey()).isEqualTo("rk1");
		assertThat(message.deathInfo()).isEqualTo(new DeathInfo("primera", "rejected"));
	}

	@Test
	void inspectQueueKeepsTheDeliveryValuesWhenThereAreNoDeathHeaders() {
		server.expect(requestTo(GET_ORIG))
			.andRespond(withSuccess("""
					[{"redelivered":false,"exchange":"ex","routing_key":"k","message_count":0,
					  "properties":{"headers":{"otra":"cabecera"}},"payload":"m","payload_encoding":"string"}]
					""", MediaType.APPLICATION_JSON));

		assertThat(topology.inspectQueue("orig", 50).messages().get(0))
			.isEqualTo(new QueueMessage("m", "ex", "k", false, null));
	}

	@Test
	void inspectQueueWarnsThatTheContentIsBinary() {
		server.expect(requestTo(GET_ORIG))
			.andRespond(withSuccess("""
					[{"payload_bytes":1,"redelivered":false,"exchange":"","routing_key":"orig","message_count":0,
					  "properties":[],"payload":"/w==","payload_encoding":"base64"}]
					""", MediaType.APPLICATION_JSON));

		assertThat(topology.inspectQueue("orig", 50).messages().get(0).body())
			.isEqualTo("[contenido binario, en base64] /w==");
	}

	@Test
	void inspectingAMissingQueueNamesIt() {
		server.expect(requestTo(GET_ORIG))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":\"not_found\",\"reason\":\"queue_not_found\"}"));

		assertThatThrownBy(() -> topology.inspectQueue("orig", 50)).isInstanceOf(NotFoundException.class)
			.hasMessage("La cola «orig» no existe.");
	}

	@Test
	void inspectQueueEncodesTheQueueName() {
		server.expect(requestTo(BASE + "/queues/%2F/a%2Fb%25c%20%C3%B1/get"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		topology.inspectQueue("a/b%c ñ", 50);

		server.verify();
	}

}
