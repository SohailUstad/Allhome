package com.allhome.colourcoats.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * A knowledge chunk found for a question.
 *
 * @param similarity cosine similarity to the question, from 0 (unrelated) to 1 (same meaning)
 * @param match which search found it
 * @param score fused ranking score (higher is better); only meaningful relative to other results of the same search
 */
public record RetrievedChunk(UUID id, String datasetId, String datasetVersion, String text, String url, String title,
		List<String> headingPath, List<String> sourceUrls, double similarity, Match match, double score) {

	public enum Match {

		/** Close in meaning (vector search). */
		VECTOR,

		/** Shares words with the question (keyword search). */
		KEYWORD,

		/** Found by both. */
		BOTH

	}

	public RetrievedChunk {
		headingPath = List.copyOf(headingPath);
		sourceUrls = List.copyOf(sourceUrls);
	}

	RetrievedChunk withRanking(Match match, double score) {
		return new RetrievedChunk(id, datasetId, datasetVersion, text, url, title, headingPath, sourceUrls, similarity,
				match, score);
	}

}
