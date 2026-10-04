package com.allhome.colourcoats.retrieval;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.FakeEmbeddingModel;
import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.ingestion.IngestionService;
import com.allhome.colourcoats.ingestion.KnowledgeArchive;
import com.allhome.colourcoats.ingestion.KnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SearchControllerTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	IngestionService ingestion;

	@Autowired
	FakeEmbeddingModel embeddingModel;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE ingestion_run CASCADE");
		embeddingModel.reset();
		ingestion.ingest(new KnowledgeArchive("colourcoats", "v1", "https://example.com/",
				List.of(new KnowledgeChunk(UUID.randomUUID(), "c1", "doc1", "Marmorino is a polished lime plaster",
						"https://example.com/", "Finishes", null, List.of("Lime"), List.of("https://example.com/#lime")))),
				"hash");
	}

	@Test
	void showsWhatTheAssistantWouldFind() throws Exception {
		mvc.perform(get("/api/search").param("q", "Do you do Marmorino?").with(httpBasic("test-operator", "test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].text").value("Marmorino is a polished lime plaster"))
			.andExpect(jsonPath("$[0].datasetVersion").value("v1"))
			.andExpect(jsonPath("$[0].sourceUrls[0]").value("https://example.com/#lime"))
			.andExpect(jsonPath("$[0].match").exists())
			.andExpect(jsonPath("$[0].similarity").isNumber());
	}

	@Test
	void requiresOperatorLogin() throws Exception {
		mvc.perform(get("/api/search").param("q", "Marmorino")).andExpect(status().isUnauthorized());
	}

	@Test
	void missingQuestionIsBadRequest() throws Exception {
		mvc.perform(get("/api/search").with(httpBasic("test-operator", "test-password")))
			.andExpect(status().isBadRequest());
	}

}
