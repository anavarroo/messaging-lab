package com.messaginglab.support;

import com.messaginglab.exception.NotFoundException;
import com.messaginglab.generator.Destination;
import com.messaginglab.generator.MessagePublisher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Publicador falso: guarda lo que recibe y deja a la prueba decidir si el destino existe y si llega a alguna parte. */
public class FakeMessagePublisher implements MessagePublisher {

	public record Sent(Destination destination, String body) {
	}

	/** Destino de las pruebas del núcleo, que no sabe de ningún sistema de mensajería. */
	public record TestDestination(String name) implements Destination {

		@Override
		public String describe() {
			return "el destino «" + name + "»";
		}

	}

	private final List<Sent> sent = new ArrayList<>();

	private final Set<String> missing = new HashSet<>();

	private boolean routed = true;

	private RuntimeException failure;

	public List<Sent> sent() {
		return sent;
	}

	public List<String> bodies() {
		return sent.stream().map(Sent::body).toList();
	}

	public void routed(boolean routed) {
		this.routed = routed;
	}

	/** El destino deja de existir. */
	public void remove(String name) {
		missing.add(name);
	}

	public void failWith(RuntimeException failure) {
		this.failure = failure;
	}

	@Override
	public void verify(Destination destination) {
		if (destination instanceof TestDestination test && missing.contains(test.name())) {
			throw new NotFoundException("No existe " + test.describe() + ".");
		}
	}

	@Override
	public boolean publish(Destination destination, String body) {
		verify(destination);
		if (failure != null) {
			throw failure;
		}
		sent.add(new Sent(destination, body));
		return routed;
	}

}
