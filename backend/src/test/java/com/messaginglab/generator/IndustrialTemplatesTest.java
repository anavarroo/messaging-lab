package com.messaginglab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.messaginglab.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IndustrialTemplatesTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static List<TemplatePreset> presets() {
		return IndustrialTemplates.all();
	}

	@Test
	void offersTheSixIndustrialTemplatesOfTheSpec() {
		assertThat(presets()).extracting(TemplatePreset::id)
			.containsExactly("robot-ur", "temperatura", "sonometro", "vibracion", "plc-tag", "alarma");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("presets")
	void everyTemplateIsValidAndAlwaysGeneratesAValidJsonBodyUnder64Kb(TemplatePreset preset) {
		MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
		PayloadGenerator generator = PayloadTemplate.parse(preset.template()).generator(new Random(7), clock);

		for (int i = 0; i < 1000; i++) {
			String body = generator.next();
			JsonNode node = JSON.readTree(body);
			assertThat(node.isObject()).as(body).isTrue();
			assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThan(PayloadTemplate.MAX_BYTES);
			clock.advance(Duration.ofMillis(100));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("presets")
	void everyTemplateHasANameADescriptionAnIntervalAndARoutingKeyLikeAnMqttTopic(TemplatePreset preset) {
		assertThat(preset.name()).isNotBlank();
		assertThat(preset.description().length()).isGreaterThan(20);
		assertThat(preset.suggestedInterval()).isNotNull();
		assertThat(preset.suggestedRoutingKey()).matches("[a-z0-9]+(\\.[a-z0-9]+)+").doesNotContain("/");
	}

	@Test
	void theIdsAreUnique() {
		Set<String> ids = new HashSet<>(presets().stream().map(TemplatePreset::id).toList());

		assertThat(ids).hasSameSizeAs(presets());
	}

	@Test
	void theRobotGivesSixJointsInRadiansAndTheStateOfItsSafety() {
		TemplatePreset robot = presets().get(0);
		PayloadGenerator generator = PayloadTemplate.parse(robot.template()).generator(new Random(3), new MutableClock(Instant.EPOCH));

		for (int i = 0; i < 300; i++) {
			JsonNode node = JSON.readTree(generator.next());
			assertThat(node.get("articulaciones_rad")).hasSize(6);
			node.get("articulaciones_rad").forEach(joint -> assertThat(joint.asDouble()).isBetween(-3.14, 3.14));
			assertThat(node.get("modo_seguridad").asString()).isIn("NORMAL", "REDUCIDO");
			assertThat(node.get("secuencia").asInt()).isEqualTo(i + 1);
		}
		assertThat(robot.suggestedInterval()).isEqualTo(Interval.fixed(100));
	}

	@Test
	void theTemperatureStaysBetween18And26DegreesAndTheNoiseBetween35And95Decibels() {
		PayloadGenerator temperature = PayloadTemplate.parse(presets().get(1).template()).generator(new Random(5), new MutableClock(Instant.EPOCH));
		PayloadGenerator noise = PayloadTemplate.parse(presets().get(2).template()).generator(new Random(5), new MutableClock(Instant.EPOCH));

		for (int i = 0; i < 2000; i++) {
			assertThat(JSON.readTree(temperature.next()).get("valor_c").asDouble()).isBetween(18.0, 26.0);
			assertThat(JSON.readTree(noise.next()).get("nivel_db").asDouble()).isBetween(35.0, 95.0);
		}
	}

	@Test
	void thePlcTagOnlyPublishesWhenItChangesAndIsTheOnlyOneThatAsksForIt() {
		List<TemplatePreset> onlyOnChange = presets().stream().filter(TemplatePreset::onlyWhenChanged).toList();

		assertThat(onlyOnChange).extracting(TemplatePreset::id).containsExactly("plc-tag");
		assertThat(onlyOnChange.get(0).template()).doesNotContain("{{ahora}}").doesNotContain("{{contador");
	}

	@Test
	void theAlarmIsSporadicWithARandomInterval() {
		TemplatePreset alarm = presets().get(5);

		assertThat(alarm.suggestedInterval().mode()).isEqualTo(Interval.Mode.RANGE);
		assertThat(alarm.suggestedInterval().minMs()).isGreaterThanOrEqualTo(1000);
	}

	@Test
	void theSuggestedIntervalsAreWithinTheAcceptedLimits() {
		presets().forEach(preset -> assertThat(preset.suggestedInterval().maxPerSecond()).isLessThanOrEqualTo(500));
	}

}
