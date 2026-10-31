package com.messaginglab.generator;

import com.messaginglab.exception.ConflictException;
import com.messaginglab.exception.LabException;
import com.messaginglab.exception.NotFoundException;
import com.messaginglab.exception.ValidationException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Emisores simulados: publican mensajes generados con una plantilla, a la frecuencia que decida el usuario, hasta
 * que se paran o alcanzan su límite. Corren en memoria y se pierden al apagar la aplicación.
 * <p>
 * Es independiente del sistema de mensajería: solo habla con {@link MessagePublisher}.
 */
public final class EmitterService {

	public static final int MAX_EMITTERS = 20;

	private static final int MAX_NAME_LENGTH = 255;

	private final MessagePublisher publisher;

	private final Scheduler scheduler;

	private final Clock clock;

	private final Supplier<RandomGenerator> randoms;

	private final GeneratorLimits limits;

	/** Protegido por el cerrojo de este servicio; el estado interno de cada emisor, por el suyo. */
	private final Map<String, Emitter> emitters = new LinkedHashMap<>();

	public EmitterService(MessagePublisher publisher, Scheduler scheduler, Clock clock,
			Supplier<RandomGenerator> randoms, GeneratorLimits limits) {
		this.publisher = publisher;
		this.scheduler = scheduler;
		this.clock = clock;
		this.randoms = randoms;
		this.limits = limits;
	}

	// ---- consulta

	public synchronized List<EmitterSnapshot> list() {
		return emitters.values().stream().map(Emitter::snapshot).toList();
	}

	public synchronized EmitterSnapshot get(String id) {
		return find(id).snapshot();
	}

	/** Mensajes por segundo que consumen los emisores en marcha, contando el peor caso de cada uno. */
	public synchronized double ratePerSecondInUse() {
		return emitters.values().stream().filter(Emitter::isRunning).mapToDouble(e -> e.spec.interval().maxPerSecond()).sum();
	}

	public GeneratorLimits limits() {
		return limits;
	}

	// ---- alta, edición y baja

	public synchronized EmitterSnapshot create(EmitterSpec spec) {
		validate(spec, null);
		if (emitters.size() >= MAX_EMITTERS) {
			throw new ConflictException("Ya hay " + MAX_EMITTERS + " emisores, que es el máximo. Elimina alguno antes.");
		}
		Emitter emitter = new Emitter(UUID.randomUUID().toString(), spec);
		emitters.put(emitter.id, emitter);
		return emitter.snapshot();
	}

	/** Edita un emisor parado; sus contadores vuelven a cero. */
	public synchronized EmitterSnapshot update(String id, EmitterSpec spec) {
		Emitter emitter = find(id);
		if (emitter.isActive()) {
			throw new ConflictException("Solo se puede editar un emisor parado. Para «" + emitter.spec.name() + "» antes.");
		}
		validate(spec, emitter);
		emitter.replace(spec);
		return emitter.snapshot();
	}

	public synchronized void delete(String id) {
		Emitter emitter = find(id);
		emitter.stop();
		emitters.remove(id);
	}

	// ---- control

	/** Pone en marcha un emisor parado, terminado o que falló; empieza de cero. */
	public synchronized EmitterSnapshot start(String id) {
		Emitter emitter = find(id);
		if (emitter.isActive()) {
			throw new ConflictException("«" + emitter.spec.name() + "» ya está en marcha o en pausa.");
		}
		requireRoom(emitter);
		publisher.verify(emitter.spec.destination());
		emitter.start();
		return emitter.snapshot();
	}

	public synchronized EmitterSnapshot pause(String id) {
		Emitter emitter = find(id);
		if (!emitter.isRunning()) {
			throw new ConflictException("«" + emitter.spec.name() + "» no está en marcha, así que no se puede pausar.");
		}
		emitter.pause();
		return emitter.snapshot();
	}

	public synchronized EmitterSnapshot resume(String id) {
		Emitter emitter = find(id);
		if (emitter.status != EmitterStatus.PAUSED) {
			throw new ConflictException("«" + emitter.spec.name() + "» no está en pausa, así que no se puede reanudar.");
		}
		requireRoom(emitter);
		publisher.verify(emitter.spec.destination());
		emitter.resume();
		return emitter.snapshot();
	}

	public synchronized EmitterSnapshot stop(String id) {
		Emitter emitter = find(id);
		if (!emitter.isActive()) {
			throw new ConflictException("«" + emitter.spec.name() + "» ya está parado.");
		}
		emitter.stop();
		return emitter.snapshot();
	}

	/** Para todos los emisores que estén en marcha o en pausa. */
	public synchronized void stopAll() {
		emitters.values().stream().filter(Emitter::isActive).forEach(Emitter::stop);
	}

	/** Cambia el límite global de mensajes por segundo. */
	public synchronized void changeLimit(int maxRatePerSecond) {
		limits.changeMaxRate(maxRatePerSecond, ratePerSecondInUse());
	}

	/** Para todo y libera el planificador, al apagar la aplicación. */
	public synchronized void shutdown() {
		stopAll();
		scheduler.shutdown();
	}

	// ---- internos

	private Emitter find(String id) {
		Emitter emitter = emitters.get(id);
		if (emitter == null) {
			throw new NotFoundException("El emisor no existe: puede que ya se haya eliminado.");
		}
		return emitter;
	}

	private void requireRoom(Emitter emitter) {
		limits.checkRoom(emitter.spec.name(), emitter.spec.interval().maxPerSecond(), ratePerSecondInUse());
	}

	private void validate(EmitterSpec spec, Emitter editing) {
		if (spec == null || spec.name() == null || spec.name().isBlank()) {
			throw new ValidationException("El nombre del emisor no puede estar vacío.");
		}
		if (spec.name().length() > MAX_NAME_LENGTH) {
			throw new ValidationException("El nombre del emisor no puede superar los " + MAX_NAME_LENGTH + " caracteres.");
		}
		if (spec.name().chars().anyMatch(Character::isISOControl)) {
			throw new ValidationException("El nombre del emisor no puede contener caracteres de control.");
		}
		if (emitters.values().stream().anyMatch(other -> other != editing && other.spec.name().equals(spec.name()))) {
			throw new ConflictException("Ya hay un emisor con el nombre «" + spec.name() + "».");
		}
		if (spec.destination() == null) {
			throw new ValidationException("Indica el destino del emisor.");
		}
		if (spec.interval() == null) {
			throw new ValidationException("Indica la frecuencia del emisor.");
		}
		if (spec.maxMessages() != null && spec.maxMessages() < 1) {
			throw new ValidationException("El número máximo de mensajes debe ser al menos 1.");
		}
		if (spec.maxDurationSeconds() != null && spec.maxDurationSeconds() < 1) {
			throw new ValidationException("La duración máxima debe ser al menos 1 segundo.");
		}
		PayloadTemplate.parse(spec.template());
	}

	private void tick(Emitter emitter, long token) {
		synchronized (emitter) {
			if (emitter.status != EmitterStatus.RUNNING || emitter.token != token) {
				return;
			}
			long now = clock.millis();
			try {
				if (emitter.durationReached(now)) {
					emitter.finish(EmitterStatus.FINISHED,
							"Ha terminado tras " + emitter.spec.maxDurationSeconds() + " segundos en marcha.");
					return;
				}
				publishNext(emitter, now);
				if (emitter.spec.maxMessages() != null && emitter.published >= emitter.spec.maxMessages()) {
					emitter.finish(EmitterStatus.FINISHED,
							"Ha publicado los " + emitter.spec.maxMessages() + " mensajes que se le pidieron.");
					return;
				}
			}
			catch (LabException exception) {
				emitter.finish(EmitterStatus.FAILED, failureMessage(emitter, exception));
				return;
			}
			catch (RuntimeException exception) {
				emitter.finish(EmitterStatus.FAILED, "Se ha parado por un error inesperado.");
				return;
			}
			emitter.scheduleNext(token);
		}
	}

	private void publishNext(Emitter emitter, long now) {
		String body = emitter.generator.next();
		if (emitter.spec.onlyWhenChanged() && body.equals(emitter.lastBody)) {
			return;
		}
		emitter.lastBody = body;
		boolean routed = publisher.publish(emitter.spec.destination(), body, emitter.spec.name());
		emitter.published++;
		if (!routed) {
			emitter.unrouted++;
		}
		emitter.meter.record(now);
	}

	private static String failureMessage(Emitter emitter, LabException exception) {
		String where = emitter.spec.destination().describe();
		return exception instanceof NotFoundException
				? "Se ha parado porque el destino (" + where + ") ya no existe."
				: "Se ha parado: " + exception.getMessage();
	}

	/** Un emisor con su estado. Los campos mutables los protege el cerrojo del propio emisor. */
	private final class Emitter {

		private final String id;

		private final RandomGenerator random = randoms.get();

		private final RateMeter meter = new RateMeter();

		private EmitterSpec spec;

		private PayloadTemplate template;

		private PayloadGenerator generator;

		private volatile EmitterStatus status = EmitterStatus.STOPPED;

		private long published;

		private long unrouted;

		private String stopMessage;

		private String lastBody;

		private long token;

		private Scheduler.Handle handle;

		/** Tiempo en marcha acumulado hasta la última pausa, y cuándo se reanudó por última vez. */
		private long activeMillis;

		private long resumedAt;

		/** Cuándo toca el siguiente envío. Se calcula sobre el ritmo previsto y no desde que termina el anterior. */
		private long nextDue;

		private Emitter(String id, EmitterSpec spec) {
			this.id = id;
			replace(spec);
		}

		private synchronized void replace(EmitterSpec newSpec) {
			this.spec = newSpec;
			this.template = PayloadTemplate.parse(newSpec.template());
			this.status = EmitterStatus.STOPPED;
			resetCounters();
		}

		private void resetCounters() {
			published = 0;
			unrouted = 0;
			stopMessage = null;
			lastBody = null;
			activeMillis = 0;
			meter.reset();
		}

		private boolean isRunning() {
			return status == EmitterStatus.RUNNING;
		}

		private boolean isActive() {
			return status == EmitterStatus.RUNNING || status == EmitterStatus.PAUSED;
		}

		private synchronized void start() {
			resetCounters();
			generator = template.generator(random, clock);
			status = EmitterStatus.RUNNING;
			resumedAt = clock.millis();
			nextDue = resumedAt;
			scheduleAfter(0);
		}

		private synchronized void pause() {
			cancel();
			activeMillis += clock.millis() - resumedAt;
			status = EmitterStatus.PAUSED;
		}

		private synchronized void resume() {
			status = EmitterStatus.RUNNING;
			resumedAt = clock.millis();
			long wait = spec.interval().next(random);
			nextDue = resumedAt + wait;
			scheduleAfter(wait);
		}

		private synchronized void stop() {
			cancel();
			if (isActive()) {
				status = EmitterStatus.STOPPED;
			}
		}

		private synchronized void finish(EmitterStatus finalStatus, String message) {
			cancel();
			status = finalStatus;
			stopMessage = message;
		}

		private void cancel() {
			token++;
			if (handle != null) {
				handle.cancel();
				handle = null;
			}
		}

		/**
		 * Programa el siguiente envío descontando lo que tardó el anterior, para que la frecuencia real sea la
		 * pedida y no la pedida más el tiempo de publicar. Si el publicador no da abasto, envía seguido (al máximo
		 * que aguanta) sin acumular retraso que luego se recupere de golpe.
		 */
		private void scheduleNext(long currentToken) {
			if (currentToken != token) {
				return;
			}
			long now = clock.millis();
			nextDue += spec.interval().next(random);
			long delay = Math.max(0, nextDue - now);
			if (nextDue < now) {
				nextDue = now;
			}
			scheduleAfter(delay);
		}

		private void scheduleAfter(long delayMs) {
			long current = ++token;
			handle = scheduler.schedule(() -> tick(this, current), delayMs);
		}

		private boolean durationReached(long now) {
			return spec.maxDurationSeconds() != null
					&& activeMillis + (now - resumedAt) >= spec.maxDurationSeconds() * 1000;
		}

		private synchronized EmitterSnapshot snapshot() {
			return new EmitterSnapshot(id, spec, status, published, unrouted, meter.perSecond(clock.millis()),
					stopMessage);
		}

	}

}
