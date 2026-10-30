package com.messaginglab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * El núcleo del generador no puede depender de RabbitMQ para que el laboratorio de Kafka lo reutilice. Se
 * comprueba, como las capas de la especificación 02, mirando lo que importa cada clase.
 */
class CoreIndependenceTest {

	private static final Path CORE = Path.of("src/main/java/com/messaginglab/generator");

	private static final List<String> FORBIDDEN = List.of("com.rabbitmq", "com.messaginglab.gateway",
			"com.messaginglab.api", "com.messaginglab.model", "com.messaginglab.generator.rabbitmq",
			"com.messaginglab.service", "org.springframework", "jakarta.");

	@Test
	void theCoreOnlyDependsOnTheJdkAndTheNeutralExceptions() throws IOException {
		try (Stream<Path> files = Files.list(CORE)) {
			List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
			assertThat(sources).hasSizeGreaterThan(10);
			for (Path source : sources) {
				List<String> imports = Files.readAllLines(source).stream().filter(line -> line.startsWith("import ")).toList();
				for (String forbidden : FORBIDDEN) {
					assertThat(imports).as(source.getFileName() + " no debe importar " + forbidden)
						.noneMatch(line -> line.startsWith("import " + forbidden) || line.startsWith("import static " + forbidden));
				}
			}
		}
	}

	@Test
	void theCheckReallyFindsAForbiddenImport(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
		Path bad = dir.resolve("Malo.java");
		Files.writeString(bad, "package x;\nimport com.rabbitmq.client.Channel;\nclass Malo {}\n");

		List<String> imports = Files.readAllLines(bad).stream().filter(line -> line.startsWith("import ")).toList();

		assertThat(imports).anyMatch(line -> line.startsWith("import " + FORBIDDEN.get(0)));
	}

}
