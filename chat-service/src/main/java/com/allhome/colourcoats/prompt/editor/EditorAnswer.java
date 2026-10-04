package com.allhome.colourcoats.prompt.editor;

import java.util.List;

/**
 * What the editor model returns (JSON).
 *
 * @param outcome PROPOSE (changes ready to review), QUESTION (the request is unclear) or REFUSE (not a prompt
 * change: a fact for the knowledge base, a locked section, or something only code can do)
 * @param summary one or two sentences describing the change, for the operator
 * @param changes the sections to change, each with its complete new text
 * @param conflicts rules elsewhere in the prompt that the change contradicts or affects
 * @param question what the editor needs to know (outcome QUESTION)
 * @param refusal why it will not change the prompt, and what to do instead (outcome REFUSE)
 */
public record EditorAnswer(String outcome, String summary, List<Change> changes, List<String> conflicts,
		String question, String refusal) {

	public EditorAnswer {
		changes = changes == null ? List.of() : List.copyOf(changes);
		conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
	}

	/**
	 * @param key the section key
	 * @param body the section's complete new text (not a fragment)
	 * @param reason why this section changes
	 */
	public record Change(String key, String body, String reason) {

	}

}
