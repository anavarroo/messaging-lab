package com.messaginglab.generator;

import com.messaginglab.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Plantilla del cuerpo de un mensaje: un texto (normalmente JSON) con valores entre dobles llaves que cambian en
 * cada envío, como {@code {{numero(20,30)}}}.
 * <p>
 * No ejecuta código: solo admite una lista cerrada de funciones (ver {@link #FUNCTIONS}). Cada valor se escribe
 * tal cual, sin comillas, así que si debe ir entre comillas se ponen en la plantilla: {@code "hora": "{{ahora}}"}.
 * <p>
 * Es independiente de cualquier sistema de mensajería.
 */
public final class PayloadTemplate {

	/** Tamaño máximo de un cuerpo generado, en bytes UTF-8 (el mismo límite que la publicación manual). */
	public static final int MAX_BYTES = 64 * 1024;

	private static final String OPEN = "{{";

	private static final String CLOSE = "}}";

	private final List<Segment> segments;

	private PayloadTemplate(List<Segment> segments) {
		this.segments = List.copyOf(segments);
	}

	/**
	 * Analiza una plantilla y la comprueba generando un cuerpo de ejemplo.
	 * @throws ValidationException si es inválida, con el motivo y el lugar (línea y columna)
	 */
	public static PayloadTemplate parse(String text) {
		if (text == null || text.isEmpty()) {
			throw new ValidationException("La plantilla no puede estar vacía.");
		}
		List<Segment> segments = new ArrayList<>();
		int position = 0;
		while (position < text.length()) {
			int open = text.indexOf(OPEN, position);
			if (open < 0) {
				segments.add(new Literal(text.substring(position)));
				break;
			}
			if (open > position) {
				segments.add(new Literal(text.substring(position, open)));
			}
			int close = text.indexOf(CLOSE, open + OPEN.length());
			if (close < 0) {
				throw error(text, open, "falta cerrar «{{» con «}}».");
			}
			String expression = text.substring(open + OPEN.length(), close).trim();
			segments.add(call(text, open, expression));
			position = close + CLOSE.length();
		}
		PayloadTemplate template = new PayloadTemplate(segments);
		template.generator(new Random(), Clock.systemUTC()).next();
		return template;
	}

	/** Generador con su propio estado (contadores, derivas...) para un emisor. */
	public PayloadGenerator generator(RandomGenerator random, Clock clock) {
		List<PayloadGenerator.Part> parts = new ArrayList<>();
		for (Segment segment : segments) {
			if (segment instanceof Literal literal) {
				parts.add(context -> literal.text());
			}
			else if (segment instanceof Call call) {
				parts.add(call.definition().create(call.args(), call));
			}
		}
		return new PayloadGenerator(parts, random, clock);
	}

	/** Nombres de las funciones admitidas, para los mensajes de error y la documentación. */
	public static List<String> functionNames() {
		return List.copyOf(FUNCTIONS.keySet());
	}

	// ---- análisis

	private static Call call(String text, int open, String expression) {
		if (expression.isEmpty()) {
			throw error(text, open, "hay un «{{}}» vacío: escribe una función, por ejemplo «{{numero(1,10)}}».");
		}
		String name = expression;
		List<String> args = List.of();
		int paren = expression.indexOf('(');
		if (paren >= 0) {
			if (!expression.endsWith(")")) {
				throw error(text, open, "falta cerrar el paréntesis en «" + expression + "».");
			}
			name = expression.substring(0, paren).trim();
			String inside = expression.substring(paren + 1, expression.length() - 1);
			args = inside.isBlank() ? List.of() : Arrays.stream(inside.split(",", -1)).map(String::trim).toList();
		}
		Definition definition = FUNCTIONS.get(name);
		if (definition == null) {
			throw error(text, open, "la función «" + name + "» no existe. Las disponibles son: "
					+ String.join(", ", FUNCTIONS.keySet()) + ".");
		}
		Call call = new Call(definition, args, text, open);
		if (args.size() < definition.minArgs() || args.size() > definition.maxArgs()) {
			throw call.error("«" + name + "» necesita " + definition.arity() + " y se han escrito " + args.size()
					+ ". Uso: " + definition.usage() + ".");
		}
		definition.create(args, call);
		return call;
	}

	private static ValidationException error(String text, int index, String reason) {
		return new ValidationException("Error en la plantilla (" + location(text, index) + "): " + reason);
	}

	private static String location(String text, int index) {
		int line = 1;
		int column = 1;
		for (int i = 0; i < index && i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				line++;
				column = 1;
			}
			else {
				column++;
			}
		}
		return "línea " + line + ", columna " + column;
	}

	// ---- segmentos

	private sealed interface Segment permits Literal, Call {

	}

	private record Literal(String text) implements Segment {

	}

	/** Una llamada a una función de la plantilla, con el lugar donde está escrita para poder explicar los errores. */
	record Call(Definition definition, List<String> args, String text, int index) implements Segment {

		ValidationException error(String reason) {
			return PayloadTemplate.error(text, index, reason);
		}

		double number(int position) {
			String raw = args.get(position);
			try {
				double value = Double.parseDouble(raw);
				if (Double.isNaN(value) || Double.isInfinite(value)) {
					throw new NumberFormatException(raw);
				}
				return value;
			}
			catch (NumberFormatException exception) {
				throw error("el argumento " + (position + 1) + " de «" + definition.name() + "» («" + raw
						+ "») no es un número. Usa el punto para los decimales, por ejemplo 2.5.");
			}
		}

		int whole(int position, int min, int max) {
			double value = number(position);
			if (value != Math.rint(value) || value < min || value > max) {
				throw error("el argumento " + (position + 1) + " de «" + definition.name() + "» debe ser un número entero entre "
						+ min + " y " + max + ".");
			}
			return (int) value;
		}

		boolean isWhole(int position) {
			return !args.get(position).contains(".") && !args.get(position).toLowerCase(Locale.ROOT).contains("e");
		}

	}

	/** Una función admitida: cuántos argumentos tiene y cómo se genera su valor. */
	record Definition(String name, int minArgs, int maxArgs, String usage, Factory factory) {

		String arity() {
			if (minArgs == maxArgs) {
				return minArgs == 0 ? "ningún argumento" : minArgs + (minArgs == 1 ? " argumento" : " argumentos");
			}
			return maxArgs == Integer.MAX_VALUE ? "al menos " + minArgs + " argumentos"
					: "entre " + minArgs + " y " + maxArgs + " argumentos";
		}

		/** Comprueba los argumentos y crea el generador de valores, que tiene su propio estado. */
		PayloadGenerator.Part create(List<String> args, Call call) {
			return factory.create(call);
		}

	}

	@FunctionalInterface
	interface Factory {

		PayloadGenerator.Part create(Call call);

	}

	// ---- las funciones

	private static final Map<String, Definition> FUNCTIONS = functions();

	private static Map<String, Definition> functions() {
		Map<String, Definition> map = new LinkedHashMap<>();
		add(map, new Definition("numero", 2, 3, "numero(mínimo, máximo[, decimales])", PayloadTemplate::numero));
		add(map, new Definition("deriva", 4, 5, "deriva(inicio, mínimo, máximo, paso[, decimales])",
				PayloadTemplate::deriva));
		add(map, new Definition("senoidal", 3, 4, "senoidal(mínimo, máximo, periodoEnSegundos[, decimales])",
				PayloadTemplate::senoidal));
		add(map, new Definition("contador", 0, 1, "contador[(inicio)]", PayloadTemplate::contador));
		add(map, new Definition("ahora", 0, 0, "ahora", call -> context -> context.now().toString()));
		add(map, new Definition("elegir", 1, Integer.MAX_VALUE, "elegir(valor1, valor2, ...)", PayloadTemplate::elegir));
		add(map, new Definition("booleano", 0, 1, "booleano[(probabilidadDeVerdadero entre 0 y 1)]",
				PayloadTemplate::booleano));
		add(map, new Definition("ejes", 3, 4, "ejes(cuántos, mínimo, máximo[, decimales])", PayloadTemplate::ejes));
		return Map.copyOf(map).entrySet().stream().sorted(Map.Entry.comparingByKey())
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
	}

	private static void add(Map<String, Definition> map, Definition definition) {
		map.put(definition.name(), definition);
	}

	private static String format(double value, int decimals) {
		String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
		return text.startsWith("-") && Double.parseDouble(text) == 0 ? text.substring(1) : text;
	}

	private static void ordered(Call call, double min, double max, int minPosition, int maxPosition) {
		if (min > max) {
			throw call.error("en «" + call.definition().name() + "» el mínimo (" + call.args().get(minPosition)
					+ ") no puede ser mayor que el máximo (" + call.args().get(maxPosition) + ").");
		}
	}

	private static PayloadGenerator.Part numero(Call call) {
		double min = call.number(0);
		double max = call.number(1);
		ordered(call, min, max, 0, 1);
		boolean integer = call.args().size() == 2 && call.isWhole(0) && call.isWhole(1);
		int decimals = call.args().size() == 3 ? call.whole(2, 0, 10) : 2;
		if (integer) {
			long low = (long) min;
			long high = (long) max;
			return context -> Long.toString(context.random().nextLong(low, high + 1));
		}
		return context -> format(min + context.random().nextDouble() * (max - min), decimals);
	}

	private static PayloadGenerator.Part deriva(Call call) {
		double start = call.number(0);
		double min = call.number(1);
		double max = call.number(2);
		double step = call.number(3);
		ordered(call, min, max, 1, 2);
		if (start < min || start > max) {
			throw call.error("en «deriva» el valor inicial (" + call.args().get(0) + ") debe estar entre el mínimo y el máximo.");
		}
		if (step <= 0) {
			throw call.error("en «deriva» el paso (" + call.args().get(3) + ") debe ser mayor que 0.");
		}
		int decimals = call.args().size() == 5 ? call.whole(4, 0, 10) : 2;
		double[] current = { start };
		return context -> {
			double next = current[0] + (context.random().nextDouble() * 2 - 1) * step;
			current[0] = Math.max(min, Math.min(max, next));
			return format(current[0], decimals);
		};
	}

	private static PayloadGenerator.Part senoidal(Call call) {
		double min = call.number(0);
		double max = call.number(1);
		double period = call.number(2);
		ordered(call, min, max, 0, 1);
		if (period <= 0) {
			throw call.error("en «senoidal» el periodo (" + call.args().get(2) + ") debe ser mayor que 0 segundos.");
		}
		int decimals = call.args().size() == 4 ? call.whole(3, 0, 10) : 2;
		double middle = (min + max) / 2;
		double amplitude = (max - min) / 2;
		return context -> format(middle + amplitude * Math.sin(2 * Math.PI * context.elapsedSeconds() / period),
				decimals);
	}

	private static PayloadGenerator.Part contador(Call call) {
		long[] next = { call.args().isEmpty() ? 1 : call.whole(0, Integer.MIN_VALUE, Integer.MAX_VALUE) };
		return context -> Long.toString(next[0]++);
	}

	private static PayloadGenerator.Part elegir(Call call) {
		List<String> options = call.args();
		for (int i = 0; i < options.size(); i++) {
			if (options.get(i).isEmpty()) {
				throw call.error("en «elegir» el valor " + (i + 1) + " está vacío.");
			}
		}
		return context -> options.get(context.random().nextInt(options.size()));
	}

	private static PayloadGenerator.Part booleano(Call call) {
		double probability = call.args().isEmpty() ? 0.5 : call.number(0);
		if (probability < 0 || probability > 1) {
			throw call.error("en «booleano» la probabilidad (" + call.args().get(0) + ") debe estar entre 0 y 1.");
		}
		return context -> Boolean.toString(context.random().nextDouble() < probability);
	}

	/** Varios ejes que se mueven poco a poco, como las articulaciones de un brazo robótico. */
	private static PayloadGenerator.Part ejes(Call call) {
		int count = call.whole(0, 1, 64);
		double min = call.number(1);
		double max = call.number(2);
		ordered(call, min, max, 1, 2);
		int decimals = call.args().size() == 4 ? call.whole(3, 0, 10) : 4;
		double step = (max - min) * 0.02;
		double[] axes = new double[count];
		Arrays.fill(axes, (min + max) / 2);
		return context -> {
			StringBuilder out = new StringBuilder("[");
			for (int i = 0; i < count; i++) {
				axes[i] = Math.max(min, Math.min(max, axes[i] + (context.random().nextDouble() * 2 - 1) * step));
				out.append(i == 0 ? "" : ", ").append(format(axes[i], decimals));
			}
			return out.append(']').toString();
		};
	}

	static int bytesOf(String body) {
		return body.getBytes(StandardCharsets.UTF_8).length;
	}

	/** Momento y azar con los que se genera un valor. */
	record Context(RandomGenerator random, Instant now, double elapsedSeconds) {

	}

}
