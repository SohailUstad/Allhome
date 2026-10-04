package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

/** The prompt pages of the operator console, rendered through the real templates, security and database. */
@IntegrationTest
class PromptConsoleTests {

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
	void promptPagesNeedTheOperatorLogin() throws Exception {
		mvc.perform(get("/prompts").accept(MediaType.TEXT_HTML))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		mvc.perform(post("/prompts/" + UUID.randomUUID() + "/activate").with(OPERATOR)).andExpect(status().isForbidden()); // no CSRF token
	}

	@Test
	void activeVersionIsShownSectionBySectionWithLocksAndHistory() throws Exception {
		UUID active = service.active().versionId();

		mvc.perform(get("/prompts").with(OPERATOR)).andExpect(redirectedUrl("/prompts/" + active));
		mvc.perform(get("/prompts/{id}", active).param("section", "language-tone").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Agent prompt")))
			.andExpect(content().string(containsString("2. LANGUAGE &amp; TONE")))
			.andExpect(content().string(containsString("Reply in the visitor&#39;s language and script")))
			.andExpect(content().string(containsString("bi-lock-fill")))
			.andExpect(content().string(containsString("bi-shield-exclamation")))
			.andExpect(content().string(containsString("/prompts/" + active + "/sections/language-tone/edit")))
			.andExpect(content().string(containsString("Initial version from classpath:prompts/sales-system.md")));
	}

	@Test
	void lockedSectionCannotBeOpenedForEditing() throws Exception {
		UUID active = service.active().versionId();

		mvc.perform(get("/prompts/{id}/sections/output/edit", active).with(OPERATOR))
			.andExpect(redirectedUrl("/prompts/" + active + "?section=output"))
			.andExpect(flash().attribute("error", containsString("locked")));
		mvc.perform(post("/prompts/{id}/sections/output", active).param("body", "plain text").with(OPERATOR).with(csrf()))
			.andExpect(flash().attribute("error", containsString("locked")));
		assertThat(service.versions()).hasSize(1);
	}

	@Test
	void editingASectionOfTheActiveVersionStartsADraftShownWithItsChanges() throws Exception {
		UUID active = service.active().versionId();

		String location = mvc
			.perform(post("/prompts/{id}/sections/complaints", active).param("body", "Say sorry once.\n<script>x</script>")
				.param("note", "Shorter")
				.with(OPERATOR)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("Saved in draft version 2")))
			.andReturn()
			.getResponse()
			.getRedirectedUrl();
		UUID draft = service.versions().getFirst().getId();
		assertThat(location).isEqualTo("/prompts/" + draft + "?section=complaints");
		assertThat(service.active().versionId()).isEqualTo(active);

		mvc.perform(get(location).with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Draft.")))
			.andExpect(content().string(containsString("Changes from version")))
			.andExpect(content().string(containsString("diff-line added")))
			.andExpect(content().string(containsString("&lt;script&gt;x&lt;/script&gt;")))
			.andExpect(content().string(not(containsString("<script>x</script>"))))
			.andExpect(content().string(containsString("Activate draft")));
	}

	@Test
	void protectedSectionNeedsTheConfirmationBox() throws Exception {
		UUID active = service.active().versionId();

		mvc.perform(post("/prompts/{id}/sections/persona", active).param("body", "HOMEOWNER — own home")
			.with(OPERATOR)
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("protected")))
			.andExpect(content().string(containsString("HOMEOWNER — own home"))); // the operator's text is kept
		assertThat(service.versions()).hasSize(1);

		mvc.perform(post("/prompts/{id}/sections/persona", active).param("body", "HOMEOWNER — own home")
			.param("confirmProtected", "true")
			.with(OPERATOR)
			.with(csrf())).andExpect(status().is3xxRedirection());
		assertThat(service.versions()).hasSize(2);
	}

	@Test
	void activateAndDiscardFromTheConsole() throws Exception {
		UUID v1 = service.active().versionId();
		UUID keep = service.startDraft(java.util.Map.of("complaints", "Keep."), null, "op", false).getId();

		mvc.perform(post("/prompts/{id}/activate", keep).with(OPERATOR).with(csrf()))
			.andExpect(redirectedUrl("/prompts/" + keep))
			.andExpect(flash().attribute("notice", containsString("is now active")));
		assertThat(service.active().versionId()).isEqualTo(keep);

		UUID drop = service.startDraft(java.util.Map.of("complaints", "Drop."), null, "op", false).getId();
		mvc.perform(post("/prompts/{id}/discard", drop).with(OPERATOR).with(csrf())).andExpect(redirectedUrl("/prompts"));
		assertThat(service.version(drop).getStatus()).isEqualTo(PromptStatus.DISCARDED);

		mvc.perform(post("/prompts/{id}/activate", v1).with(OPERATOR).with(csrf())) // roll back
			.andExpect(flash().attribute("notice", containsString("Version 1 is now active")));
		mvc.perform(get("/prompts/{id}", UUID.randomUUID()).with(OPERATOR)).andExpect(status().isNotFound());
	}

	@Test
	void sectionsAreAddedAndRemovedFromTheConsole() throws Exception {
		UUID active = service.active().versionId();
		mvc.perform(get("/prompts/{id}/sections/new", active).param("after", "complaints").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Insert after")));
		mvc.perform(post("/prompts/{id}/sections", active).param("title", "Festive offers")
			.param("body", " ")
			.param("after", "complaints")
			.with(OPERATOR)
			.with(csrf())).andExpect(status().isOk()).andExpect(content().string(containsString("cannot be empty")));

		String location = mvc
			.perform(post("/prompts/{id}/sections", active).param("title", "Festive offers")
				.param("body", "Only mention offers listed in KNOWLEDGE.")
				.param("after", "complaints")
				.with(OPERATOR)
				.with(csrf()))
			.andExpect(flash().attribute("notice", containsString("added to draft version 2")))
			.andReturn()
			.getResponse()
			.getRedirectedUrl();
		UUID draft = service.versions().getFirst().getId();
		assertThat(location).isEqualTo("/prompts/" + draft + "?section=festive-offers");

		mvc.perform(post("/prompts/{id}/sections/attachments/remove", draft).with(OPERATOR).with(csrf()))
			.andExpect(redirectedUrl("/prompts/" + draft));
		mvc.perform(post("/prompts/{id}/sections/persona/remove", draft).with(OPERATOR).with(csrf()))
			.andExpect(flash().attribute("error", containsString("cannot be removed")));
		mvc.perform(get("/prompts/{id}", draft).param("section", "festive-offers").with(OPERATOR))
			.andExpect(content().string(containsString("Removed:")))
			.andExpect(content().string(containsString(">12. ATTACHMENTS</span>")))
			.andExpect(content().string(containsString("Festive offers")))
			.andExpect(content().string(containsString("Remove section")));
	}

}
