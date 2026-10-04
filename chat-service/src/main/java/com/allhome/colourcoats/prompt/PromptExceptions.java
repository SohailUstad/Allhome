package com.allhome.colourcoats.prompt;

import java.util.List;
import java.util.UUID;

/** Errors of prompt editing, each with a message the operator can act on. */
public final class PromptExceptions {

	private PromptExceptions() {
	}

	/** 404. */
	public static class NotFound extends RuntimeException {

		NotFound(UUID id) {
			super("Prompt version " + id + " does not exist");
		}

	}

	/** 400: the change breaks a rule (locked section, empty section, too long, nothing changed). */
	public static class RuleViolation extends RuntimeException {

		RuleViolation(String message) {
			super(message);
		}

	}

	/** 400: protected sections were changed without the operator confirming it. */
	public static class ConfirmationRequired extends RuleViolation {

		private final List<String> sections;

		ConfirmationRequired(List<String> sections) {
			super("These sections are protected; confirm that you want to change them: " + String.join(", ", sections));
			this.sections = List.copyOf(sections);
		}

		public List<String> sections() {
			return sections;
		}

	}

	/** 409: the version is not in a state that allows this (for example the active version changed meanwhile). */
	public static class Conflict extends RuntimeException {

		Conflict(String message) {
			super(message);
		}

	}

}
