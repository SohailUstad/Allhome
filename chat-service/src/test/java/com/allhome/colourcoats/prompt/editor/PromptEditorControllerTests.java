package com.allhome.colourcoats.prompt.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/** The editor's console endpoints through the real security rules (the shared fake model never proposes anything). */
@IntegrationTest
class PromptEditorControllerTests {

	private static final RequestPostProcessor OPERATOR = user("test-operator").roles("OPERATOR");

	@Autowired
	MockMvc mvc;

	@Autowired
	PromptService prompts;

	@Autowired
	PromptEditRepository edits;

	@Autowired
	EditorModels models;

	@Autowired
	PromptEditorProperties properties;

	@Autowired
	ResourceLoader resources;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		PromptTables.reset(jdbc);
	}

	@Test
	void editorEndpointsNeedTheOperatorAndACsrfToken() throws Exception {
		var active = prompts.activeVersion().getId();
		mvc.perform(get("/prompts/ai-editor/models").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().is4xxClientError());
		mvc.perform(post("/prompts/{id}/ai-edits", active).with(OPERATOR)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instruction\":\"x\"}")).andExpect(status().isForbidden());
	}

	@Test
	void modelChoicesComeFromTheConfiguration() throws Exception {
		mvc.perform(get("/prompts/ai-editor/models").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].model.id").value("gpt-4.1"))
			.andExpect(jsonPath("$[0].model.reasoning").value(false))
			.andExpect(jsonPath("$[0].preselected").value(true))
			.andExpect(jsonPath("$[1].model.reasoning").value(true))
			.andExpect(jsonPath("$[1].model.efforts[0]").value("low"));
	}

	@Test
	void askingReturnsTheEditorsAnswerAndRecordsIt() throws Exception {
		var active = prompts.activeVersion().getId();

		mvc.perform(post("/prompts/{id}/ai-edits", active).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instruction\":\"Shorter replies\",\"section\":\"any\",\"model\":\"gpt-4.1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED")) // the shared fake model answers like the chat agent
			.andExpect(jsonPath("$.model").value("gpt-4.1"));
		mvc.perform(get("/prompts/{id}/ai-edits", active).with(OPERATOR))
			.andExpect(jsonPath("$[0].instruction").value("Shorter replies"))
			.andExpect(jsonPath("$[0].createdBy").value("test-operator"));

		mvc.perform(post("/prompts/{id}/ai-edits", active).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instruction\":\"x\",\"section\":\"output\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("locked")));
		mvc.perform(post("/prompts/{id}/ai-edits", active).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instruction\":\"x\",\"model\":\"some-other-model\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("not offered")));
	}

	@Test
	void acceptAndDiscardReturnToThePromptPage() throws Exception {
		var active = prompts.activeVersion().getId();
		var scripted = mock(ChatModel.class);
		when(scripted.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
				"{\"outcome\":\"PROPOSE\",\"summary\":\"s\",\"changes\":[{\"key\":\"complaints\",\"body\":\"New.\","
						+ "\"reason\":\"r\"}],\"conflicts\":[]}")))));
		var editor = new PromptEditorService(prompts, edits, models, scripted, properties, resources, transactions);
		var accepted = editor.propose(active, "Change complaints", null, null, null, "test-operator");
		var discarded = editor.propose(active, "Change complaints again", null, null, null, "test-operator");

		String draftUrl = mvc.perform(post("/prompts/ai-edits/{id}/accept", accepted.id()).with(OPERATOR).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("draft version 2")))
			.andReturn()
			.getResponse()
			.getRedirectedUrl();
		assertThat(draftUrl).isEqualTo("/prompts/" + prompts.versions().getFirst().getId());

		mvc.perform(post("/prompts/ai-edits/{id}/discard", discarded.id()).with(OPERATOR).with(csrf()))
			.andExpect(redirectedUrl("/prompts/" + active));
		mvc.perform(get("/prompts/ai-edits/{id}", discarded.id()).with(OPERATOR))
			.andExpect(jsonPath("$.status").value("DISCARDED"))
			.andExpect(jsonPath("$.outdated").value(false));

		mvc.perform(get(draftUrl).with(OPERATOR))
			.andExpect(content().string(containsString("Ask AI to change the prompt")))
			.andExpect(content().string(containsString("/js/prompt-editor.js")));
	}

}
