package com.messaginglab.model;

import java.util.List;

/**
 * Resultado de inspeccionar una cola sin consumirla.
 * @param total mensajes listos que tiene la cola
 * @param truncated hay más mensajes listos de los que se muestran
 */
public record QueueInspection(List<QueueMessage> messages, long total, boolean truncated) {

}
