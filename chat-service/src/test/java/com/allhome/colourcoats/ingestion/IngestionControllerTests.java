package com.allhome.colourcoats.ingestion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class IngestionControllerTests {

	private static final RequestPostProcessor OPERATOR = httpBasic("test-operator", "test-password");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("TRUNCATE ingestion_run CASCADE");
	}

	@Test
	void uploadStoresNewVersionThenRecognisesRepeat() throws Exception {
		MockMultipartFile zip = file(pythonPackagedZip());

		mvc.perform(multipart("/api/ingestions").file(zip).with(OPERATOR))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("INGESTED"))
			.andExpect(jsonPath("$.datasetId").value("colourcoats"))
			.andExpect(jsonPath("$.datasetVersion").value("2026-10-03"))
			.andExpect(jsonPath("$.chunkCount").value(2))
			.andExpect(jsonPath("$.active").value(true));

		mvc.perform(multipart("/api/ingestions").file(zip).with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALREADY_INGESTED"));
	}

	@Test
	void listShowsStoredVersionsAndActivateRollsBack() throws Exception {
		mvc.perform(multipart("/api/ingestions").file(file(zip("v1", "first"))).with(OPERATOR))
			.andExpect(status().isCreated());
		mvc.perform(multipart("/api/ingestions").file(file(zip("v2", "second"))).with(OPERATOR))
			.andExpect(status().isCreated());

		String v1RunId = jdbc.queryForObject("SELECT id FROM ingestion_run WHERE dataset_version = 'v1'", String.class);
		mvc.perform(get("/api/ingestions").param("datasetId", "colourcoats").with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].datasetVersion").value("v2"))
			.andExpect(jsonPath("$[0].active").value(true))
			.andExpect(jsonPath("$[1].active").value(false));

		mvc.perform(post("/api/ingestions/{runId}/activate", v1RunId).with(OPERATOR))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVATED"))
			.andExpect(jsonPath("$.datasetVersion").value("v1"));
	}

	@Test
	void invalidArchiveIsBadRequestWithReason() throws Exception {
		mvc.perform(multipart("/api/ingestions").file(file("not a zip".getBytes(StandardCharsets.UTF_8))).with(OPERATOR))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("Invalid knowledge archive"))
			.andExpect(jsonPath("$.detail").value("ZIP must contain manifest.json and chunks.jsonl"));

		mvc.perform(multipart("/api/ingestions").file(file(new byte[0])).with(OPERATOR))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Upload a non-empty ZIP file in the 'file' field"));
	}

	@Test
	void missingFilePartIsBadRequest() throws Exception {
		mvc.perform(multipart("/api/ingestions").with(OPERATOR))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void sameVersionWithDifferentContentIsConflict() throws Exception {
		mvc.perform(multipart("/api/ingestions").file(file(zip("v1", "first"))).with(OPERATOR))
			.andExpect(status().isCreated());

		mvc.perform(multipart("/api/ingestions").file(file(zip("v1", "changed"))).with(OPERATOR))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.title").value("Dataset version already exists"));
	}

	@Test
	void activatingUnknownRunIsNotFound() throws Exception {
		mvc.perform(post("/api/ingestions/{runId}/activate", UUID.randomUUID()).with(OPERATOR))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.title").value("Ingestion run not found"));
	}

	@Test
	void operatorEndpointsRequireLogin() throws Exception {
		mvc.perform(get("/api/ingestions")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ingestions").with(httpBasic("test-operator", "wrong"))).andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/ingestions").file(file(pythonPackagedZip()))).andExpect(status().isUnauthorized());
	}

	@Test
	void healthCheckIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	private static MockMultipartFile file(byte[] content) {
		return new MockMultipartFile("file", "knowledge.zip", "application/zip", content);
	}

	private static byte[] pythonPackagedZip() throws IOException {
		try (InputStream in = IngestionControllerTests.class.getResourceAsStream("/ingestion/python-packaged.zip")) {
			return in.readAllBytes();
		}
	}

	/** A minimal valid archive with one chunk whose text identifies the content. */
	private static byte[] zip(String version, String text) throws IOException {
		String manifest = """
				{"schema_version": "1.0", "dataset_id": "colourcoats", "dataset_version": "%s",
				 "source": "https://example.com/", "chunks_file": "chunks.jsonl", "chunk_count": 1}
				""".formatted(version);
		String chunk = """
				{"id": "c1", "document_id": "doc1", "text": "%s", "url": "https://example.com/"}
				""".formatted(text);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(manifest.getBytes(StandardCharsets.UTF_8));
			zip.putNextEntry(new ZipEntry("chunks.jsonl"));
			zip.write(chunk.getBytes(StandardCharsets.UTF_8));
		}
		return out.toByteArray();
	}

}
