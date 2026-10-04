package com.allhome.colourcoats.livemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.prompt.PromptExceptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class LiveModelTests {

	private static final RequestPostProcessor OPERATOR = user("test-operator").roles("OPERATOR");

	@Autowired
	LiveModelService service;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void reset() {
		jdbc.update("UPDATE chat_message SET model_change_id = NULL");
		jdbc.update("DELETE FROM live_model_change");
	}

	@Test
	void configuredModelIsLiveUntilTheOperatorChangesIt() {
		assertThat(service.current().model()).isEqualTo("gpt-4.1-mini");
		assertThat(service.current().changeId()).isNull();

		var change = service.change("gpt-4.1", "Mini misclassifies Hinglish personas", true, "op");

		assertThat(service.current().model()).isEqualTo("gpt-4.1");
		assertThat(service.current().changeId()).isEqualTo(change.getId());
		assertThat(change.getPreviousModel()).isEqualTo("gpt-4.1-mini");
		assertThat(service.history()).extracting(LiveModelChange::getReason)
			.containsExactly("Mini misclassifies Hinglish personas");
	}

	@Test
	void changeNeedsAnAllowedModelAReasonAndConfirmation() {
		assertThatThrownBy(() -> service.change("o3", "Reasoning model for live chats", true, "op"))
			.isInstanceOf(PromptExceptions.RuleViolation.class)
			.hasMessageContaining("not allowed");
		assertThatThrownBy(() -> service.change("gpt-4.1", "too short", true, "op")).hasMessageContaining("at least 15");
		assertThatThrownBy(() -> service.change("gpt-4.1", "A perfectly fine reason", false, "op"))
			.hasMessageContaining("Confirm");
		assertThatThrownBy(() -> service.change("gpt-4.1-mini", "Already live, nothing to do", true, "op"))
			.hasMessageContaining("already");
		assertThat(service.history()).isEmpty();
	}

	@Test
	void everyReplyRecordsTheModelChangeInForce() throws Exception {
		var change = service.change("gpt-4.1", "Testing replies with the larger model", true, "op");

		mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"Hello\",\"channel\":\"WEB_CHAT\"}")).andExpect(status().isOk());

		assertThat(jdbc.queryForObject(
				"SELECT model_change_id FROM chat_message WHERE role = 'ASSISTANT' ORDER BY id DESC LIMIT 1", UUID.class))
			.isEqualTo(change.getId());
	}

	@Test
	void consolePageWarnsAndRecordsTheReason() throws Exception {
		mvc.perform(get("/agent-model").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("This affects every live conversation")))
			.andExpect(content().string(containsString("gpt-4.1-mini")));

		mvc.perform(post("/agent-model").param("model", "gpt-4.1").param("reason", "short").with(OPERATOR).with(csrf()))
			.andExpect(flash().attribute("error", containsString("at least 15")));
		mvc.perform(post("/agent-model").param("model", "gpt-4.1")
			.param("reason", "Better persona accuracy in evals")
			.param("confirmed", "true")
			.with(OPERATOR)
			.with(csrf())).andExpect(flash().attribute("notice", containsString("gpt-4.1 now answers visitors")));

		mvc.perform(get("/agent-model").with(OPERATOR))
			.andExpect(content().string(containsString("Better persona accuracy in evals")));
		mvc.perform(post("/agent-model").param("model", "gpt-4o-mini").with(OPERATOR)).andExpect(status().isForbidden());
	}

}
