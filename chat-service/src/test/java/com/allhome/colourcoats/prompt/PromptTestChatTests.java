package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.allhome.colourcoats.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The console's test chat: the real agent with a draft prompt, through the real security rules; nothing stored. */
@IntegrationTest
class PromptTestChatTests {

	private static final RequestPostProcessor OPERATOR = user("test-operator").roles("OPERATOR");

	@Autowired
	MockMvc mvc;

	@Autowired
	PromptService service;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		PromptTables.reset(jdbc);
	}

	@Test
	void draftCanBeTriedWithoutCreatingAConversationOrLead() throws Exception {
		var draft = service.startDraft(Map.of("complaints", "Draft rule."), null, "op", false);
		int conversations = count("chat_conversation");
		int messages = count("chat_message");

		mvc.perform(post("/prompts/{id}/test-chat", draft.getId()).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"message":"Do you do lime wash?","channel":"INSTAGRAM",
					 "history":[{"role":"USER","content":"Hi"},{"role":"ASSISTANT","content":"Hello!"},
					            {"role":"SYSTEM","content":"ignored"}],
					 "lead":{"persona":"HOMEOWNER","intent":"BROWSING","city":"Pune"}}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reply").value("Test reply"))
			.andExpect(jsonPath("$.handoff").value(false))
			.andExpect(jsonPath("$.lead.city").value("Pune")) // kept across turns
			.andExpect(jsonPath("$.lead.persona").value("HOMEOWNER"))
			.andExpect(jsonPath("$.leadStatus").value("ENGAGED"))
			.andExpect(jsonPath("$.promptVersion").value(draft.getVersionNumber()));

		assertThat(count("chat_conversation")).isEqualTo(conversations);
		assertThat(count("chat_message")).isEqualTo(messages);
	}

	@Test
	void invalidRequestsAreRejected() throws Exception {
		var active = service.active().versionId();
		mvc.perform(post("/prompts/{id}/test-chat", active).with(OPERATOR)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"hi\"}")).andExpect(status().isForbidden()); // no CSRF token
		mvc.perform(post("/prompts/{id}/test-chat", active).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"  \"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/prompts/{id}/test-chat", active).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"hi\",\"channel\":\"FAX\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(Matchers.containsString("FAX")));
		mvc.perform(post("/prompts/{id}/test-chat", UUID.randomUUID()).with(OPERATOR)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"hi\"}")).andExpect(status().isNotFound());
	}

	@Test
	void promptPageOffersTheTestChat() throws Exception {
		var active = service.active().versionId();
		mvc.perform(get("/prompts/{id}", active).with(OPERATOR))
			.andExpect(content().string(Matchers.containsString("/prompts/" + active + "/test-chat")))
			.andExpect(content().string(Matchers.containsString("/js/prompt-test-chat.js")));
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
