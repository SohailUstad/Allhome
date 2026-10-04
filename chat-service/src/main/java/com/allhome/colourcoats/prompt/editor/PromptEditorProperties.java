package com.allhome.colourcoats.prompt.editor;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The AI that helps the operator edit the agent's prompt. It never answers visitors.
 *
 * @param instructions the editor's own fixed instructions (kept in git, not editable from the console)
 * @param defaultModel model preselected in the console
 * @param models the models the operator may choose from
 * @param maxOutputTokens upper limit of a reply, including a reasoning model's hidden reasoning
 * @param timeout how long one request may take before it is abandoned
 * @param maxInstructionLength longest instruction or feedback the operator can send, in characters
 * @param modelsUrl the OpenAI endpoint listing the models the API key can use
 */
@ConfigurationProperties("prompt-editor")
public record PromptEditorProperties(@DefaultValue("classpath:prompts/prompt-editor.md") String instructions,
		@DefaultValue("gpt-4.1") String defaultModel, @DefaultValue List<Model> models,
		@DefaultValue("25000") int maxOutputTokens, @DefaultValue("170s") Duration timeout,
		@DefaultValue("4000") int maxInstructionLength,
		@DefaultValue("https://api.openai.com/v1/models") String modelsUrl) {

	/**
	 * @param id the OpenAI model id
	 * @param label what the operator sees
	 * @param description speed and cost, in a few words
	 * @param reasoning true for reasoning models: they take a reasoning effort and no temperature
	 * @param efforts reasoning efforts offered (reasoning models only)
	 * @param defaultEffort effort preselected in the console
	 */
	public record Model(String id, String label, String description, boolean reasoning, List<String> efforts,
			String defaultEffort) {

		public Model {
			label = label == null || label.isBlank() ? id : label;
			efforts = reasoning ? (efforts == null || efforts.isEmpty() ? List.of("low", "medium", "high") : List.copyOf(efforts))
					: List.of();
			defaultEffort = reasoning ? (defaultEffort != null && efforts.contains(defaultEffort) ? defaultEffort
					: efforts.contains("medium") ? "medium" : efforts.getFirst()) : null;
		}

	}

}
