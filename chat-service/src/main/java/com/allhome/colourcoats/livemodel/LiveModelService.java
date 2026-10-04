package com.allhome.colourcoats.livemodel;

import java.time.Clock;
import java.util.List;

import com.allhome.colourcoats.chat.LiveModels;
import com.allhome.colourcoats.prompt.PromptExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The model that answers visitors. Changed by the operator in the console (with a required reason), effective from
 * each conversation's next message. Cached in memory; other instances notice a change within the refresh interval.
 */
@Service
public class LiveModelService implements LiveModels {

	private static final Logger log = LoggerFactory.getLogger(LiveModelService.class);

	private final LiveModelChangeRepository changes;

	private final LiveModelProperties properties;

	private final String defaultModel;

	private final Clock clock = Clock.systemUTC();

	private volatile Cached cached;

	LiveModelService(LiveModelChangeRepository changes, LiveModelProperties properties,
			@Value("${spring.ai.openai.chat.model:gpt-4.1-mini}") String defaultModel) {
		this.changes = changes;
		this.properties = properties;
		this.defaultModel = defaultModel;
	}

	@Override
	public LiveModel current() {
		Cached snapshot = cached;
		if (snapshot != null && System.nanoTime() - snapshot.loadedAt() < properties.refreshInterval().toNanos()) {
			return snapshot.model();
		}
		LiveModel model;
		try {
			model = changes.findFirstByOrderByChangedAtDesc()
				.map(change -> new LiveModel(change.getModel(), change.getId()))
				.orElse(new LiveModel(defaultModel, null));
		}
		catch (RuntimeException ex) {
			model = snapshot != null ? snapshot.model() : new LiveModel(defaultModel, null);
			log.warn("Could not check the live model, using {}: {}", model.model(), ex.toString());
		}
		cached = new Cached(model, System.nanoTime());
		return model;
	}

	/** Makes another allowed model live for every conversation, from its next message. */
	public LiveModelChange change(String model, String reason, boolean confirmed, String by) {
		if (properties.models().stream().noneMatch(allowed -> allowed.id().equals(model))) {
			throw new PromptExceptions.RuleViolation("Model '" + model + "' is not allowed for live conversations");
		}
		String why = reason == null ? "" : reason.strip();
		if (why.length() < properties.minReasonLength()) {
			throw new PromptExceptions.RuleViolation(
					"Give a reason of at least " + properties.minReasonLength() + " characters");
		}
		if (!confirmed) {
			throw new PromptExceptions.RuleViolation("Confirm that this changes the model for all live conversations");
		}
		String previous = current().model();
		if (previous.equals(model)) {
			throw new PromptExceptions.RuleViolation(model + " is already the live model");
		}
		LiveModelChange change = changes.saveAndFlush(new LiveModelChange(model, previous, why, by, clock.instant()));
		cached = new Cached(new LiveModel(model, change.getId()), System.nanoTime());
		log.warn("{} changed the live chat model from {} to {}: {}", by, previous, model, why);
		return change;
	}

	public List<LiveModelChange> history() {
		return changes.findTop50ByOrderByChangedAtDesc();
	}

	public List<LiveModelProperties.Model> allowed() {
		return properties.models();
	}

	public String defaultModel() {
		return defaultModel;
	}

	private record Cached(LiveModel model, long loadedAt) {

	}

}
