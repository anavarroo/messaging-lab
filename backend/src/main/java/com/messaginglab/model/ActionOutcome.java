package com.messaginglab.model;

/** Qué ha pasado finalmente con un mensaje tras la acción del usuario. */
public enum ActionOutcome {

	CONFIRMED, REQUEUED, DEAD_LETTERED, DISCARDED

}
