package com.messaginglab.assistant;

/** De dónde sale la foto del laboratorio. Se pide en cada pregunta, para que refleje el estado de ese momento. */
public interface SnapshotProvider {

	Snapshot current();

}
