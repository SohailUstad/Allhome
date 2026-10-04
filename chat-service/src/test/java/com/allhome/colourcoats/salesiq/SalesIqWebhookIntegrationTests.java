package com.allhome.colourcoats.salesiq;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/** The copied webhook against the real Flyway schema and the security rules (no login; signatures off in tests). */
@IntegrationTest
class SalesIqWebhookIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void greetsThenAnswersWebsiteAndInstagramMessages() throws Exception {
		mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON)
				.content("{\"handler\":\"trigger\",\"visitor\":{\"active_conversation_id\":\"it-web\"}}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.action").value("reply"))
			.andExpect(jsonPath("$.replies[0]").value(org.hamcrest.Matchers.startsWith("Hi there!")));

		mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content("""
				{"handler":"message","visitor":{"active_conversation_id":"it-web","channel":"Website"},
				 "message":{"text":"Do you do Marmorino?"},"request":{"id":"r1"}}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.action").value("reply"))
			.andExpect(jsonPath("$.replies[0]").value("Test reply"));

		mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content("""
				{"handler":"message","visitor":{"active_conversation_id":"it-ig","channel":"Instagram","name":"Priya S"},
				 "message":{"text":"price?"}}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replies[0]").value("Test reply"));

		assertThat(jdbc.queryForList("SELECT channel FROM chat_conversation ORDER BY channel", String.class))
			.contains("INSTAGRAM", "ZOHO_SALESIQ");
		assertThat(jdbc.queryForObject("SELECT name FROM chat_lead l JOIN chat_conversation c ON c.id = l.conversation_id "
				+ "WHERE c.channel = 'INSTAGRAM'", String.class)).isEqualTo("Priya S");
	}

}
