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

	/** Núcleos sin dependencias de infraestructura: generador, visualizador de flujo, diagnóstico y control de servicios. */
	private static final List<Path> CORES = List.of(Path.of("src/main/java/com/messaginglab/generator"),
			Path.of("src/main/java/com/messaginglab/flow"), Path.of("src/main/java/com/messaginglab/diagnostics"),
			Path.of("src/main/java/com/messaginglab/control"));

	private static final List<String> FORBIDDEN = List.of("com.rabbitmq", "com.messaginglab.gateway",
			"com.messaginglab.api", "com.messaginglab.model", "com.messaginglab.generator.rabbitmq",
			"com.messaginglab.service", "com.messaginglab.flow.rabbitmq", "org.springframework", "jakarta.");

	@Test
	void theCoreOnlyDependsOnTheJdkAndTheNeutralExceptions() throws IOException {
		for (Path core : CORES) {
			try (Stream<Path> files = Files.list(core)) {
				List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
				// si la carpeta estuviera vacía, la ruta estaría mal escrita y la prueba no comprobaría nada
				assertThat(sources).as(core.toString()).isNotEmpty();
				for (Path source : sources) {
					List<String> imports = Files.readAllLines(source).stream().filter(line -> line.startsWith("import ")).toList();
					for (String forbidden : FORBIDDEN) {
						assertThat(imports).as(source.getFileName() + " no debe importar " + forbidden)
							.noneMatch(line -> line.startsWith("import " + forbidden) || line.startsWith("import static " + forbidden));
					}
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
