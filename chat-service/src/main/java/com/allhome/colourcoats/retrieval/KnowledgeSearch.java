package com.allhome.colourcoats.retrieval;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.ingestion.KnowledgeChunkEntity;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds the knowledge chunks most relevant to a question. Only the active version of each dataset is searched; this
 * class is the single place that rule lives, so callers cannot search old versions by mistake.
 */
@Service
public class KnowledgeSearch {

	/**
	 * Nearest chunks by cosine distance ({@code <=>}), using the HNSW index. The active-version filter is applied to
	 * the index results, so the scan must continue past inactive chunks: see {@link #ITERATIVE_SCAN}.
	 */
	private static final String VECTOR_SEARCH = """
			SELECT c.id, r.dataset_id, r.dataset_version, c.text, c.url, c.title, c.heading_path, c.source_urls,
			       c.embedding <=> CAST(:query AS vector) AS distance
			FROM knowledge_chunk c
			JOIN ingestion_run r ON r.id = c.ingestion_run_id
			WHERE r.active
			ORDER BY c.embedding <=> CAST(:query AS vector)
			LIMIT :limit
			""";

	/**
	 * Without this, the HNSW index returns only its first candidates (ef_search, 40 by default) and the active filter
	 * can leave fewer than {@code topK} results once older versions are stored. pgvector 0.8+ keeps scanning, in exact
	 * distance order, until enough rows pass the filter.
	 */
	private static final String ITERATIVE_SCAN = "SET LOCAL hnsw.iterative_scan = strict_order";

	private final EmbeddingModel embeddingModel;

	private final JdbcClient jdbc;

	private final TransactionTemplate readOnlyTransaction;

	private final RetrievalProperties properties;

	KnowledgeSearch(EmbeddingModel embeddingModel, JdbcClient jdbc, PlatformTransactionManager transactionManager,
			RetrievalProperties properties) {
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
		this.readOnlyTransaction = new TransactionTemplate(transactionManager);
		this.readOnlyTransaction.setReadOnly(true);
		this.properties = properties;
	}

	/**
	 * @return up to {@code retrieval.top-k} chunks, most similar first, each at least
	 * {@code retrieval.min-similarity}; empty for a blank question
	 */
	public List<RetrievedChunk> search(String question) {
		if (question == null || question.isBlank()) {
			return List.of();
		}
		float[] query = embed(question);
		List<RetrievedChunk> nearest = readOnlyTransaction.execute(status -> {
			jdbc.sql(ITERATIVE_SCAN).update();
			return jdbc.sql(VECTOR_SEARCH)
				.param("query", query)
				.param("limit", properties.topK())
				.query(KnowledgeSearch::toChunk)
				.list();
		});
		return nearest.stream().filter(chunk -> chunk.similarity() >= properties.minSimilarity()).toList();
	}

	private float[] embed(String question) {
		float[] vector = embeddingModel.embed(question);
		if (vector.length != KnowledgeChunkEntity.EMBEDDING_DIMENSIONS) {
			throw new IllegalStateException("Embedding model returned %d dimensions, expected %d"
				.formatted(vector.length, KnowledgeChunkEntity.EMBEDDING_DIMENSIONS));
		}
		return vector;
	}

	private static RetrievedChunk toChunk(ResultSet row, int rowNumber) throws SQLException {
		return new RetrievedChunk(row.getObject("id", UUID.class), row.getString("dataset_id"),
				row.getString("dataset_version"), row.getString("text"), row.getString("url"), row.getString("title"),
				strings(row.getArray("heading_path")), strings(row.getArray("source_urls")),
				1 - row.getDouble("distance"));
	}

	private static List<String> strings(Array array) throws SQLException {
		return Arrays.asList((String[]) array.getArray());
	}

}
