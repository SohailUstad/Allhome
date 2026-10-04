package com.allhome.colourcoats.retrieval;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.allhome.colourcoats.ingestion.KnowledgeChunkEntity;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds the knowledge chunks most relevant to a question with hybrid search: vector search (closeness in meaning) and
 * keyword search (shared words, which catches exact names embeddings handle poorly), merged with
 * {@link ReciprocalRankFusion}. Only the active version of each dataset is searched; this class is the single place
 * that rule lives, so callers cannot search old versions by mistake.
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
	 * Chunks containing any of the question's words (OR), using the full-text index, best text rank first. Also
	 * returns the cosine distance, so every result reports its similarity.
	 */
	private static final String KEYWORD_SEARCH = """
			SELECT c.id, r.dataset_id, r.dataset_version, c.text, c.url, c.title, c.heading_path, c.source_urls,
			       c.embedding <=> CAST(:query AS vector) AS distance
			FROM knowledge_chunk c
			JOIN ingestion_run r ON r.id = c.ingestion_run_id
			CROSS JOIN to_tsquery('simple', :terms) AS terms
			WHERE r.active AND c.search_vector @@ terms
			ORDER BY ts_rank_cd(c.search_vector, terms) DESC, c.id
			LIMIT :limit
			""";

	/** The question's words as stored in the search column: stop words removed, reduced to their stem. */
	private static final String QUESTION_TERMS = "SELECT lexeme FROM unnest(to_tsvector('english', :question))";

	/**
	 * Without this, the HNSW index returns only its first candidates (ef_search, 40 by default) and the active filter
	 * can leave fewer results than asked for once older versions are stored. pgvector 0.8+ keeps scanning, in exact
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
	 * @return up to {@code retrieval.top-k} chunks, best first; empty for a blank question. Vector matches below
	 * {@code retrieval.min-similarity} are left out.
	 */
	public List<RetrievedChunk> search(String question) {
		if (question == null || question.isBlank()) {
			return List.of();
		}
		float[] query = embed(question);
		return readOnlyTransaction.execute(status -> {
			List<RetrievedChunk> vectorHits = vectorSearch(query);
			List<RetrievedChunk> keywordHits = keywordSearch(question, query);
			return ReciprocalRankFusion.fuse(vectorHits, keywordHits, properties.topK());
		});
	}

	private List<RetrievedChunk> vectorSearch(float[] query) {
		jdbc.sql(ITERATIVE_SCAN).update();
		return jdbc.sql(VECTOR_SEARCH)
			.param("query", query)
			.param("limit", properties.candidates())
			.query(KnowledgeSearch::toChunk)
			.list()
			.stream()
			.filter(chunk -> chunk.similarity() >= properties.minSimilarity())
			.toList();
	}

	private List<RetrievedChunk> keywordSearch(String question, float[] query) {
		List<String> terms = jdbc.sql(QUESTION_TERMS).param("question", question).query(String.class).list();
		if (terms.isEmpty()) {
			return List.of(); // only stop words, e.g. "what is it?"
		}
		return jdbc.sql(KEYWORD_SEARCH)
			.param("query", query)
			.param("terms", anyOf(terms))
			.param("limit", properties.candidates())
			.query(KnowledgeSearch::toChunk)
			.list();
	}

	/** {@code 'term1' | 'term2'}: tsquery syntax, each term quoted so punctuation in it is taken literally. */
	private static String anyOf(List<String> terms) {
		return terms.stream().map(term -> "'" + term.replace("'", "''") + "'").collect(Collectors.joining(" | "));
	}

	private float[] embed(String question) {
		float[] vector = embeddingModel.embed(question);
		if (vector.length != KnowledgeChunkEntity.EMBEDDING_DIMENSIONS) {
			throw new IllegalStateException("Embedding model returned %d dimensions, expected %d"
				.formatted(vector.length, KnowledgeChunkEntity.EMBEDDING_DIMENSIONS));
		}
		return vector;
	}

	/** Match and score are set by {@link ReciprocalRankFusion}. */
	private static RetrievedChunk toChunk(ResultSet row, int rowNumber) throws SQLException {
		return new RetrievedChunk(row.getObject("id", UUID.class), row.getString("dataset_id"),
				row.getString("dataset_version"), row.getString("text"), row.getString("url"), row.getString("title"),
				strings(row.getArray("heading_path")), strings(row.getArray("source_urls")),
				1 - row.getDouble("distance"), null, 0);
	}

	private static List<String> strings(Array array) throws SQLException {
		return Arrays.asList((String[]) array.getArray());
	}

}
