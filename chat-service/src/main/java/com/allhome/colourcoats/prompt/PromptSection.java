package com.allhome.colourcoats.prompt;

/**
 * One part of the system prompt, as the operator sees and edits it.
 *
 * @param key stable identifier, kept when the title changes (for example {@code language-tone})
 * @param title the heading text without {@code ## }; {@code null} for the text before the first heading
 * @param body the section's text, without its heading
 */
public record PromptSection(String key, String title, String body) {

	public boolean hasHeading() {
		return title != null;
	}

	PromptSection withBody(String newBody) {
		return new PromptSection(key, title, newBody);
	}

}
