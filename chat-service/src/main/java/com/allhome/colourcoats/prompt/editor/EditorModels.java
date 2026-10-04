package com.allhome.colourcoats.prompt.editor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.allhome.colourcoats.prompt.PromptExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The editor models the operator may choose from, and which of them the OpenAI key can actually use (asked once at
 * startup; if that check fails, every configured model is offered and a wrong one fails when used).
 */
@Component
public class EditorModels {

	private static final Logger log = LoggerFactory.getLogger(EditorModels.class);

	private final PromptEditorProperties properties;

	private final RestClient http;

	private final String apiKey;

	private volatile Set<String> available; // null = not known

	EditorModels(PromptEditorProperties properties, @Value("${spring.ai.openai.api-key:}") String apiKey) {
		this.properties = properties;
		this.http = RestClient.create();
		this.apiKey = apiKey;
	}

	/** In the background, so a slow or failing check never delays startup. */
	@EventListener(ApplicationReadyEvent.class)
	public void checkAtStartup() {
		if (apiKey == null || apiKey.isBlank() || properties.models().isEmpty()) {
			return;
		}
		Thread.ofVirtual().name("editor-models").start(this::refresh);
	}

	void refresh() {
		try {
			JsonNode body = http.get()
				.uri(properties.modelsUrl())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.retrieve()
				.body(JsonNode.class);
			Set<String> ids = new HashSet<>();
			if (body != null) {
				for (JsonNode model : body.path("data")) {
					ids.add(model.path("id").asString());
				}
			}
			setAvailable(ids);
			var missing = properties.models().stream().map(PromptEditorProperties.Model::id).filter(id -> !ids.contains(id)).toList();
			if (!missing.isEmpty()) {
				log.warn("The OpenAI key cannot use these prompt-editor models, they are hidden: {}", missing);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not list the OpenAI models (all configured editor models are offered): {}", ex.toString());
		}
	}

	void setAvailable(Set<String> ids) {
		available = ids == null ? null : Set.copyOf(ids);
	}

	/** Every configured model, with whether the key can use it ({@code null} when unknown). */
	public List<Choice> choices() {
		Set<String> known = available;
		return properties.models()
			.stream()
			.map(model -> new Choice(model, known == null ? null : known.contains(model.id()),
					model.id().equals(properties.defaultModel())))
			.toList();
	}

	/**
	 * The model and reasoning effort for a request, checked against the configuration: nobody can send an arbitrary
	 * model name or a setting the model does not take.
	 */
	public Selection select(String modelId, String effort) {
		String id = modelId == null || modelId.isBlank() ? properties.defaultModel() : modelId;
		PromptEditorProperties.Model model = properties.models()
			.stream()
			.filter(candidate -> candidate.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new PromptExceptions.RuleViolation("Model '" + id + "' is not offered for prompt editing"));
		Set<String> known = available;
		if (known != null && !known.contains(model.id())) {
			throw new PromptExceptions.RuleViolation("The OpenAI key cannot use model '" + model.id() + "'");
		}
		if (!model.reasoning()) {
			return new Selection(model, null);
		}
		String chosen = effort == null || effort.isBlank() ? model.defaultEffort() : effort;
		if (!model.efforts().contains(chosen)) {
			throw new PromptExceptions.RuleViolation(
					"Model '" + model.id() + "' takes reasoning effort " + model.efforts() + ", not '" + chosen + "'");
		}
		return new Selection(model, chosen);
	}

	/** @param available whether the API key can use the model; {@code null} when that is not known */
	public record Choice(PromptEditorProperties.Model model, Boolean available, boolean preselected) {

	}

	public record Selection(PromptEditorProperties.Model model, String effort) {

	}

}
