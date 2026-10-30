package com.messaginglab.support;

import com.messaginglab.gateway.BrokerPublisher;
import com.messaginglab.model.PublishRequest;
import com.messaginglab.model.PublishResult;
import java.util.ArrayList;
import java.util.List;

/** Publicador falso: guarda lo que recibe y responde con el resultado configurado. */
public class RecordingBrokerPublisher implements BrokerPublisher {

	private final List<PublishRequest> published = new ArrayList<>();

	private boolean routed = true;

	public void routed(boolean routed) {
		this.routed = routed;
	}

	public List<PublishRequest> published() {
		return published;
	}

	@Override
	public PublishResult publish(PublishRequest request) {
		published.add(request);
		return new PublishResult(routed);
	}

}
