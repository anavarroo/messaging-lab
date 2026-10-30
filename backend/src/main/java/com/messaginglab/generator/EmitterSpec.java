package com.messaginglab.generator;

/**
 * Lo que define un emisor.
 * @param maxMessages si no es {@code null}, el emisor termina tras publicar este número de mensajes
 * @param maxDurationSeconds si no es {@code null}, el emisor termina tras este tiempo en marcha
 * @param onlyWhenChanged no publica un cuerpo igual al anterior
 */
public record EmitterSpec(String name, Destination destination, Interval interval, String template,
		Long maxMessages, Long maxDurationSeconds, boolean onlyWhenChanged) {

}
