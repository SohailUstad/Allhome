package com.allhome.colourcoats.prompt.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.allhome.colourcoats.prompt.PromptExceptions;
import org.junit.jupiter.api.Test;

class EditorModelsTests {

	static final PromptEditorProperties PROPERTIES = new PromptEditorProperties("classpath:prompts/prompt-editor.md",
			"gpt-4.1",
			List.of(new PromptEditorProperties.Model("gpt-4.1", "GPT-4.1", "fast", false, List.of("high"), "high"),
					new PromptEditorProperties.Model("o3", null, "slow", true, null, null),
					new PromptEditorProperties.Model("gpt-5", "GPT-5", "slowest", true, List.of("low", "high"), "high")),
			25000, Duration.ofSeconds(170), 4000, "http://localhost/models");

	final EditorModels models = new EditorModels(PROPERTIES, "");

	@Test
	void standardModelsTakeNoReasoningSettings() {
		var model = PROPERTIES.models().get(0);
		assertThat(model.efforts()).isEmpty();
		assertThat(model.defaultEffort()).isNull();
		assertThat(models.select("gpt-4.1", "high").effort()).isNull(); // ignored, never sent
		assertThat(models.select(null, null).model().id()).isEqualTo("gpt-4.1"); // the default
	}

	@Test
	void reasoningModelsGetAValidEffort() {
		assertThat(PROPERTIES.models().get(1).label()).isEqualTo("o3");
		assertThat(PROPERTIES.models().get(1).efforts()).containsExactly("low", "medium", "high");
		assertThat(models.select("o3", null).effort()).isEqualTo("medium");
		assertThat(models.select("gpt-5", null).effort()).isEqualTo("high");
		assertThat(models.select("gpt-5", "low").effort()).isEqualTo("low");
		assertThatThrownBy(() -> models.select("gpt-5", "medium")).isInstanceOf(PromptExceptions.RuleViolation.class)
			.hasMessageContaining("[low, high]");
	}

	@Test
	void onlyConfiguredModelsTheKeyCanUseAreAccepted() {
		assertThatThrownBy(() -> models.select("gpt-4o-search-preview", null))
			.isInstanceOf(PromptExceptions.RuleViolation.class)
			.hasMessageContaining("not offered");
		assertThat(models.choices()).extracting(EditorModels.Choice::available).containsOnlyNulls(); // not known yet

		models.setAvailable(Set.of("gpt-4.1", "gpt-5"));

		assertThatThrownBy(() -> models.select("o3", null)).hasMessageContaining("cannot use model 'o3'");
		assertThat(models.choices()).extracting(EditorModels.Choice::available).containsExactly(true, false, true);
		assertThat(models.choices()).extracting(EditorModels.Choice::preselected).containsExactly(true, false, false);
	}

}
