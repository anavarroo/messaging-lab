package com.messaginglab.model;

/**
 * Por qué un mensaje de una dead-letter queue llegó hasta ella.
 * @param originQueue cola de la que salió
 * @param reason motivo, por ejemplo {@code rejected}
 */
public record DeathInfo(String originQueue, String reason) {

}
