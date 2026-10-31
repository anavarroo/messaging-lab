package com.messaginglab.assistant;

import java.text.Normalizer;
import java.util.Locale;

/** Texto en minúsculas y sin tildes, para comparar palabras sin que importe cómo se escriban. */
final class Words {

	private Words() {
	}

	static String normalize(String text) {
		return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
	}

}
