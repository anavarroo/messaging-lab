package com.messaginglab.model;

/** Consumidor activo de una sesión. */
public record ConsumerInfo(String id, String name, String queue, AckMode ackMode) {

}
