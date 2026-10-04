package com.allhome.colourcoats.chat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The copied chat code against the real Flyway schema (V4) and the security rules. */
@IntegrationTest
class ChatApiIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Test
	void websiteChatIsPublicAndTranscriptIsStoredForOperators() throws Exception {
		String body = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Do you do Marmorino?\",\"channel\":\"WEB_CHAT\"}"))
			.andExpect(status().isOk())
			.andExpect(header().exists("X-Request-Id"))
			.andExpect(jsonPath("$.reply").value("Test reply"))
			.andExpect(jsonPath("$.handoff").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String conversationId = JsonMapper.builder().build().readTree(body).get("conversationId").asString();

		mvc.perform(get("/api/chat/{id}/messages", conversationId)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/chat/{id}/messages", conversationId).with(httpBasic("test-operator", "test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].role").value("USER"))
			.andExpect(jsonPath("$[0].content").value("Do you do Marmorino?"))
			.andExpect(jsonPath("$[1].role").value("ASSISTANT"))
			.andExpect(jsonPath("$[1].content").value("Test reply"));
	}

}
