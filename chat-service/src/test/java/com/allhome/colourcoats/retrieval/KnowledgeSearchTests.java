package com.allhome.colourcoats.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.DoubleStream;

import com.allhome.colourcoats.FakeEmbeddingModel;
import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.ingestion.IngestionService;
import com.allhome.colourcoats.ingestion.KnowledgeArchive;
import com.allhome.colourcoats.ingestion.KnowledgeChunk;
import com.allhome.colourcoats.ingestion.KnowledgeChunkEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class KnowledgeSearchTests {

	private static final String QUESTION = "Which finish suits a bedroom?";

	/** Chunk texts carry their intended cosine similarity to the question, e.g. "s=0.90 #3". */
	private static final Pattern SIMILARITY = Pattern.compile("s=([0-9.]+)");

	@Autowired
	KnowledgeSearch search;

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
		embeddingModel.useVectors(KnowledgeSearchTests::vectorFor);
	}

	@Test
	void returnsMostSimilarChunksFirstWithTheirSimilarity() {
		ingest("colourcoats", "v1", 0.5, 0.9, 0.7);

		List<RetrievedChunk> found = search.search(QUESTION);

		assertThat(found).extracting(RetrievedChunk::similarity)
			.satisfiesExactly(s -> assertThat(s).isCloseTo(0.9, within(1e-4)),
					s -> assertThat(s).isCloseTo(0.7, within(1e-4)), s -> assertThat(s).isCloseTo(0.5, within(1e-4)));
		RetrievedChunk best = found.get(0);
		assertThat(best.datasetId()).isEqualTo("colourcoats");
		assertThat(best.datasetVersion()).isEqualTo("v1");
		assertThat(best.text()).startsWith("s=0.90");
		assertThat(best.url()).isEqualTo("https://example.com/");
		assertThat(best.headingPath()).containsExactly("Section");
		assertThat(best.sourceUrls()).containsExactly("https://example.com/#section");
	}

	@Test
	void dropsChunksBelowMinimumSimilarity() {
		ingest("colourcoats", "v1", 0.8, 0.29, 0.1);

		assertThat(search.search(QUESTION)).extracting(RetrievedChunk::text).singleElement().asString().startsWith("s=0.80");
	}

	@Test
	void returnsAtMostTopK() {
		ingest("colourcoats", "v1", DoubleStream.iterate(0.40, s -> s + 0.05).limit(10).toArray());

		assertThat(search.search(QUESTION)).hasSize(6)
			.first()
			.extracting(RetrievedChunk::similarity)
			.satisfies(s -> assertThat(s).isCloseTo(0.85, within(1e-4)));
	}

	@Test
	void searchesOnlyTheActiveVersion() {
		ingest("colourcoats", "v1", 0.95, 0.94);
		ingest("colourcoats", "v2", 0.6, 0.55); // now active

		assertThat(search.search(QUESTION)).hasSize(2).allSatisfy(chunk -> assertThat(chunk.datasetVersion()).isEqualTo("v2"));
	}

	@Test
	void searchesTheActiveVersionOfEveryDataset() {
		ingest("colourcoats", "v1", 0.8);
		ingest("brochure", "v1", 0.7);

		assertThat(search.search(QUESTION)).extracting(RetrievedChunk::datasetId).containsExactly("colourcoats", "brochure");
	}

	/**
	 * Many stored older versions sit closer to the question than every active chunk. The HNSW index alone returns its
	 * first 40 candidates (all inactive) and the filter would leave nothing; iterative scanning must keep going.
	 */
	@Test
	void returnsFullResultsWhenOlderVersionsAreCloser() {
		for (int version = 1; version <= 5; version++) {
			ingest("colourcoats", "old-" + version, DoubleStream.generate(() -> 0.97).limit(300).toArray());
		}
		ingest("colourcoats", "current", DoubleStream.iterate(0.60, s -> s - 0.01).limit(10).toArray());

		List<RetrievedChunk> found = search.search(QUESTION);

		assertThat(found).hasSize(6).allSatisfy(chunk -> assertThat(chunk.datasetVersion()).isEqualTo("current"));
		assertThat(found.get(0).similarity()).isCloseTo(0.60, within(1e-4));
	}

	@Test
	void blankQuestionFindsNothingWithoutCallingTheModel() {
		ingest("colourcoats", "v1", 0.9);
		int requests = embeddingModel.requests();

		assertThat(search.search("  ")).isEmpty();
		assertThat(search.search(null)).isEmpty();
		assertThat(embeddingModel.requests()).isEqualTo(requests);
	}

	private void ingest(String datasetId, String version, double... similarities) {
		List<KnowledgeChunk> chunks = new java.util.ArrayList<>();
		for (int i = 0; i < similarities.length; i++) {
			chunks.add(new KnowledgeChunk(UUID.randomUUID(), "c" + i, "doc1",
					"s=%.2f #%d %s %s".formatted(similarities[i], i, datasetId, version), "https://example.com/", "Page",
					null, List.of("Section"), List.of("https://example.com/#section")));
		}
		ingestion.ingest(new KnowledgeArchive(datasetId, version, "https://example.com/", chunks),
				datasetId + "-" + version);
	}

	/**
	 * The question is the unit vector e0. A chunk with intended similarity s gets s*e0 + sqrt(1-s^2)*u, where u is a
	 * random unit vector orthogonal to e0, so its cosine similarity to the question is exactly s.
	 */
	private static float[] vectorFor(String text) {
		float[] vector = new float[KnowledgeChunkEntity.EMBEDDING_DIMENSIONS];
		Matcher matcher = SIMILARITY.matcher(text);
		if (!matcher.find()) {
			vector[0] = 1; // the question
			return vector;
		}
		double similarity = Double.parseDouble(matcher.group(1));
		Random random = new Random(text.hashCode());
		double norm = 0;
		for (int i = 1; i < vector.length; i++) {
			vector[i] = (float) random.nextGaussian();
			norm += vector[i] * vector[i];
		}
		double scale = Math.sqrt(1 - similarity * similarity) / Math.sqrt(norm);
		for (int i = 1; i < vector.length; i++) {
			vector[i] *= (float) scale;
		}
		vector[0] = (float) similarity;
		return vector;
	}

}
