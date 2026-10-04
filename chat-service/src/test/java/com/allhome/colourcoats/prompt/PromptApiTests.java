package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class PromptApiTests {

	private static final RequestPostProcessor OPERATOR = httpBasic("test-operator", "test-password");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("UPDATE chat_message SET prompt_version_id = NULL");
		jdbc.update("DELETE FROM prompt_version");
	}

	@Test
	void promptApiIsForOperatorsOnly() throws Exception {
		mvc.perform(get("/api/prompts")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/prompts/active")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/prompts").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void activeVersionIsShownInSectionsWithTheirLocks() throws Exception {
		mvc.perform(get("/api/prompts/active").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version.versionNumber").value(1))
			.andExpect(jsonPath("$.version.status").value("ACTIVE"))
			.andExpect(jsonPath("$.sections[0].key").value("role"))
			.andExpect(jsonPath("$.sections[0].title").value("Introduction"))
			.andExpect(jsonPath("$.sections[0].lock").value("NONE"))
			.andExpect(jsonPath("$.sections[1].key").value("input"))
			.andExpect(jsonPath("$.sections[1].lock").value("LOCKED"))
			.andExpect(jsonPath("$.sections[4].key").value("strict-grounding"))
			.andExpect(jsonPath("$.sections[4].lock").value("PROTECTED"))
			.andExpect(jsonPath("$.content").isString());
	}

	@Test
	void draftEditActivateThroughTheApi() throws Exception {
		String created = mvc
			.perform(post("/api/prompts").with(OPERATOR)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"sections\":{\"complaints\":\"Apologise, then hand off.\"},\"note\":\"Simpler\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version.status").value("DRAFT"))
			.andExpect(jsonPath("$.version.versionNumber").value(2))
			.andExpect(jsonPath("$.version.createdBy").value("test-operator"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonMapper.builder().build().readTree(created).get("version").get("id").asString();

		mvc.perform(patch("/api/prompts/{id}", id).with(OPERATOR)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"sections\":{\"persona\":\"HOMEOWNER only\"}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.title").value("Confirmation required"))
			.andExpect(jsonPath("$.protectedSections[0]").value("persona"));
		mvc.perform(patch("/api/prompts/{id}", id).with(OPERATOR)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"sections\":{\"output\":\"plain text\"}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("locked")));

		mvc.perform(post("/api/prompts/{id}/activate", id).with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.activatedBy").value("test-operator"));
		mvc.perform(get("/api/prompts").with(OPERATOR))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].versionNumber").value(2))
			.andExpect(jsonPath("$[1].status").value("ARCHIVED"));
		mvc.perform(post("/api/prompts/{id}/discard", id).with(OPERATOR)).andExpect(status().isConflict());
		mvc.perform(get("/api/prompts/{id}", UUID.randomUUID()).with(OPERATOR)).andExpect(status().isNotFound());
	}

	@Test
	void everyReplyRecordsThePromptVersionThatProducedIt() throws Exception {
		mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"Do you do Marmorino?\",\"channel\":\"WEB_CHAT\"}")).andExpect(status().isOk());

		UUID activeId = jdbc.queryForObject("SELECT id FROM prompt_version WHERE status = 'ACTIVE'", UUID.class);
		assertThat(jdbc.queryForObject(
				"SELECT prompt_version_id FROM chat_message WHERE role = 'ASSISTANT' ORDER BY id DESC LIMIT 1",
				UUID.class))
			.isEqualTo(activeId);
	}

}
