package com.messaginglab.model;

/**
 * Resultado de crear un elemento.
 * @param created {@code false} si ya existía idéntico y no se ha duplicado
 */
public record Creation<T>(T value, boolean created) {

}
