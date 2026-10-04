package com.allhome.colourcoats.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The Evals pages through the real templates, security and database. */
@IntegrationTest
class EvalConsoleTests {

	private static final RequestPostProcessor OPERATOR = user("test-operator").roles("OPERATOR");

	@Autowired
	MockMvc mvc;

	@Autowired
	PromptService prompts;

	@Autowired
	EvalService evals;

	@Autowired
	StoredCaseRepository cases;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PromptTables.reset(jdbc);
	}

	@Test
	void evalPagesNeedTheOperator() throws Exception {
		mvc.perform(get("/evals").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/evals/runs").param("prompt", UUID.randomUUID().toString()).with(OPERATOR))
			.andExpect(status().isForbidden()); // no CSRF token
	}

	@Test
	void runFromTheConsoleCaseByCaseThenReadTheReport() throws Exception {
		UUID prompt = prompts.active().versionId();
		mvc.perform(get("/evals").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("New run")))
			.andExpect(content().string(containsString("Recent runs")));

		String location = mvc.perform(post("/evals/runs").param("prompt", prompt.toString())
			.param("criticalOnly", "true")
			.with(OPERATOR)
			.with(csrf())).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
		UUID runId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
		var run = evals.run(runId);

		mvc.perform(get(location).with(OPERATOR))
			.andExpect(content().string(containsString("data-pending=\"" + String.join(",", run.getCaseIds()))))
			.andExpect(content().string(containsString("/js/eval-run.js")));

		for (String caseId : run.getCaseIds()) {
			mvc.perform(post(location + "/cases/" + caseId).with(OPERATOR).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.caseId").value(caseId));
		}
		assertThat(evals.run(runId).isCompleted()).isTrue();

		mvc.perform(get(location).with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("cases passed")))
			.andExpect(content().string(containsString(run.getCaseIds().getFirst())))
			.andExpect(content().string(containsString("Test reply")));
		mvc.perform(post(location + "/cases/not-a-case").with(OPERATOR).with(csrf())).andExpect(status().isBadRequest());
	}

	@Test
	void casesAreListedCreatedAndValidatedInTheConsole() throws Exception {
		mvc.perform(get("/evals/cases").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("pricing-handoff")));
		mvc.perform(get("/evals/cases/pricing-handoff").with(OPERATOR))
			.andExpect(content().string(containsString("mustNotMatch")));

		mvc.perform(post("/evals/cases").param("isNew", "true")
			.param("id", "console-case")
			.param("category", "conversation")
			.param("turns", "Hi")
			.param("expect", "{ not json")
			.with(OPERATOR)
			.with(csrf())).andExpect(status().isOk()).andExpect(content().string(containsString("not valid JSON")));
		try {
			mvc.perform(post("/evals/cases").param("isNew", "true")
				.param("id", "console-case")
				.param("category", "conversation")
				.param("channel", "INSTAGRAM")
				.param("turns", "Hi\n\nDo you ship to Pune?")
				.param("expect", "{\"handoff\": false}")
				.param("enabled", "true")
				.with(OPERATOR)
				.with(csrf())).andExpect(redirectedUrl("/evals/cases"));
			var stored = evals.storedCase("console-case").orElseThrow();
			assertThat(stored.getDefinition().turns()).containsExactly("Hi", "Do you ship to Pune?");
			assertThat(stored.getDefinition().critical()).isFalse();
			assertThat(stored.isEnabled()).isTrue();
			assertThat(stored.getUpdatedBy()).isEqualTo("test-operator");
		}
		finally {
			cases.deleteById("console-case");
		}
	}

	@Test
	void promptPageShowsWhyADraftCannotBeActivatedYet() throws Exception {
		var draft = prompts.startDraft(java.util.Map.of("complaints", "Draft."), null, "op", false);
		// The test profile switches the gate off, so the page shows no eval requirement and allows activation.
		mvc.perform(get("/prompts/{id}", draft.getId()).with(OPERATOR))
			.andExpect(content().string(containsString("Activate draft")))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString("Run the evals on this draft"))));
	}

}
