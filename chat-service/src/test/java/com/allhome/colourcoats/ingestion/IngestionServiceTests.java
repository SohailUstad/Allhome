package com.allhome.colourcoats.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.allhome.colourcoats.FakeEmbeddingModel;
import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class IngestionServiceTests {

	@Autowired
	IngestionService service;

	@Autowired
	IngestionRunRepository runs;

	@Autowired
	KnowledgeChunkRepository chunks;

	@Autowired
	FakeEmbeddingModel embeddingModel;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("TRUNCATE ingestion_run CASCADE");
		embeddingModel.reset();
	}

	@Test
	void storesNewVersionAsActiveRunWithEmbeddings() {
		IngestionResult result = service.ingest(archive("colourcoats", "v1", 2), "hash-v1");

		assertThat(result.status()).isEqualTo(IngestionResult.Status.INGESTED);
		assertThat(result.active()).isTrue();
		assertThat(result.chunkCount()).isEqualTo(2);
		IngestionRun run = runs.findById(result.runId()).orElseThrow();
		assertThat(run.isActive()).isTrue();
		assertThat(run.getArchiveSha256()).isEqualTo("hash-v1");
		assertThat(run.getActivatedAt()).isNotNull();

		List<KnowledgeChunkEntity> stored = chunks.findByRunIdOrderByChunkId(run.getId());
		assertThat(stored).extracting(KnowledgeChunkEntity::getChunkId).containsExactly("c0", "c1");
		KnowledgeChunkEntity first = stored.get(0);
		assertThat(first.getHeadingPath()).containsExactly("Section", "Sub");
		assertThat(first.getSourceUrls()).containsExactly("https://example.com/#c0");
		assertThat(first.getEmbedding()).containsExactly(FakeEmbeddingModel.vector(first.getText()));
	}

	@Test
	void newVersionBecomesActiveAndOlderVersionsAreKept() {
		UUID v1 = service.ingest(archive("colourcoats", "v1", 2), "hash-v1").runId();
		UUID v2 = service.ingest(archive("colourcoats", "v2", 3), "hash-v2").runId();

		assertThat(runs.findById(v1).orElseThrow().isActive()).isFalse();
		assertThat(runs.findById(v2).orElseThrow().isActive()).isTrue();
		assertThat(chunks.countByRunId(v1)).isEqualTo(2);
		assertThat(chunks.countByRunId(v2)).isEqualTo(3);
	}

	@Test
	void datasetsHaveIndependentActiveVersions() {
		UUID colourcoats = service.ingest(archive("colourcoats", "v1", 1), "hash-a").runId();
		UUID brochure = service.ingest(archive("brochure", "v1", 1), "hash-b").runId();

		assertThat(runs.findById(colourcoats).orElseThrow().isActive()).isTrue();
		assertThat(runs.findById(brochure).orElseThrow().isActive()).isTrue();
	}

	@Test
	void repeatedUploadOfSameVersionAndContentChangesNothing() {
		UUID first = service.ingest(archive("colourcoats", "v1", 2), "hash-v1").runId();
		int requestsAfterFirst = embeddingModel.requests();

		IngestionResult again = service.ingest(archive("colourcoats", "v1", 2), "hash-v1");

		assertThat(again.status()).isEqualTo(IngestionResult.Status.ALREADY_INGESTED);
		assertThat(again.runId()).isEqualTo(first);
		assertThat(embeddingModel.requests()).isEqualTo(requestsAfterFirst);
		assertThat(runs.count()).isEqualTo(1);
	}

	@Test
	void sameVersionWithDifferentContentIsRejected() {
		service.ingest(archive("colourcoats", "v1", 2), "hash-v1");

		assertThatThrownBy(() -> service.ingest(archive("colourcoats", "v1", 5), "other-hash"))
			.isInstanceOf(VersionConflictException.class)
			.hasMessageContaining("Version 'v1' of dataset 'colourcoats' already exists");
		assertThat(runs.count()).isEqualTo(1);
		assertThat(chunks.count()).isEqualTo(2);
	}

	@Test
	void failedEmbeddingStoresNothingAndKeepsCurrentVersionActive() {
		UUID v1 = service.ingest(archive("colourcoats", "v1", 2), "hash-v1").runId();
		embeddingModel.failWith(new IllegalStateException("OpenAI unavailable"));

		assertThatThrownBy(() -> service.ingest(archive("colourcoats", "v2", 2), "hash-v2"))
			.hasMessageContaining("OpenAI unavailable");

		assertThat(runs.findByDatasetIdAndDatasetVersion("colourcoats", "v2")).isEmpty();
		assertThat(runs.findByDatasetIdAndActiveTrue("colourcoats").orElseThrow().getId()).isEqualTo(v1);
		assertThat(chunks.count()).isEqualTo(2);
	}

	@Test
	void activatingAnOlderVersionRollsBack() {
		UUID v1 = service.ingest(archive("colourcoats", "v1", 1), "hash-v1").runId();
		UUID v2 = service.ingest(archive("colourcoats", "v2", 1), "hash-v2").runId();

		IngestionResult result = service.activate(v1);

		assertThat(result.status()).isEqualTo(IngestionResult.Status.ACTIVATED);
		assertThat(result.active()).isTrue();
		assertThat(runs.findById(v1).orElseThrow().isActive()).isTrue();
		assertThat(runs.findById(v2).orElseThrow().isActive()).isFalse();
		// Activating the active run is harmless.
		assertThat(service.activate(v1).active()).isTrue();
	}

	@Test
	void activatingUnknownRunFails() {
		UUID unknown = UUID.randomUUID();
		assertThatThrownBy(() -> service.activate(unknown)).isInstanceOf(IngestionRunNotFoundException.class)
			.hasMessageContaining(unknown.toString());
	}

	@Test
	void embedsInConfiguredBatches() {
		service.ingest(archive("colourcoats", "v1", 70), "hash-v1"); // batch size 32 -> 32 + 32 + 6

		assertThat(embeddingModel.requests()).isEqualTo(3);
		assertThat(chunks.count()).isEqualTo(70);
	}

	@Test
	void databaseAllowsOnlyOneActiveRunPerDataset() {
		service.ingest(archive("colourcoats", "v1", 1), "hash-v1");

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ingestion_run (id, dataset_id, dataset_version, source, archive_sha256, chunk_count, active,
				                           ingested_at)
				VALUES (?, 'colourcoats', 'v2', 'https://example.com/', 'x', 1, true, now())
				""", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	private static KnowledgeArchive archive(String datasetId, String version, int chunkCount) {
		List<KnowledgeChunk> chunks = IntStream.range(0, chunkCount)
			.mapToObj(i -> new KnowledgeChunk(KnowledgeArchiveReader.stableId(datasetId, version, "c" + i), "c" + i,
					"doc1", "Page\nSection > Sub\n\nText %d of %s %s".formatted(i, datasetId, version),
					"https://example.com/", "Page", "2026-10-03T10:00:00+00:00", List.of("Section", "Sub"),
					List.of("https://example.com/#c" + i)))
			.toList();
		return new KnowledgeArchive(datasetId, version, "https://example.com/", chunks);
	}

}
