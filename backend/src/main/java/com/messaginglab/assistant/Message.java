package com.messaginglab.assistant;

/** Una intervención de la conversación, o las instrucciones con las que se le habla al modelo. */
public record Message(Role role, String text) {

	public enum Role {

		SYSTEM, USER, ASSISTANT

	}

}
