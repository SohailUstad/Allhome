package org.springframework.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.allhome.colourcoats.prompt.editor.EditorModels;
import com.allhome.colourcoats.prompt.editor.PromptEditorProperties;
import com.allhome.colourcoats.prompt.editor.PromptEditorService;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The exact OpenAI request the prompt editor sends (built here, never sent). Lives in Spring AI's package to reach
 * {@code OpenAiChatModel.createRequest}: a reasoning model must get a reasoning effort and no temperature, a standard
 * model temperature 0, and both JSON mode. The chat agent's own defaults (temperature 0.4) must not leak in.
 */
class EditorRequestShapeTests {

	static final PromptEditorProperties PROPERTIES = new PromptEditorProperties("classpath:prompts/prompt-editor.md",
			"gpt-4.1", List.of(), 25000, java.time.Duration.ofSeconds(170), 4000, "http://localhost/models");

	final OpenAiChatModel chatAgentModel = OpenAiChatModel.builder()
		.options(OpenAiChatOptions.builder().apiKey("test-key").model("gpt-4.1-mini").temperature(0.4).build())
		.build();

	ChatCompletionCreateParams request(PromptEditorProperties.Model model, String effort) {
		var options = PromptEditorService.requestOptions(new EditorModels.Selection(model, effort), PROPERTIES);
		return chatAgentModel.createRequest(new Prompt(List.of(new UserMessage("Answer in JSON")), options), false);
	}

	@Test
	void reasoningModelRequestHasEffortAndNoTemperature() {
		var request = request(new PromptEditorProperties.Model("gpt-5", null, null, true, null, null), "high");

		assertThat(request.model().asString()).isEqualTo("gpt-5");
		assertThat(request.reasoningEffort()).contains(ReasoningEffort.HIGH);
		assertThat(request.temperature()).isEmpty();
		assertThat(request.maxCompletionTokens()).contains(25000L);
		assertThat(request.responseFormat().orElseThrow().isJsonObject()).isTrue();
	}

	@Test
	void standardModelRequestHasTemperatureZeroAndNoEffort() {
		var request = request(new PromptEditorProperties.Model("gpt-4.1", null, null, false, null, null), null);

		assertThat(request.model().asString()).isEqualTo("gpt-4.1");
		assertThat(request.temperature()).contains(0.0);
		assertThat(request.reasoningEffort()).isEmpty();
		assertThat(request.responseFormat().orElseThrow().isJsonObject()).isTrue();
	}

}
