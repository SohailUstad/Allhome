package com.allhome.colourcoats.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * A knowledge chunk found for a question.
 *
 * @param similarity cosine similarity to the question, from 0 (unrelated) to 1 (same meaning)
 */
public record RetrievedChunk(UUID id, String datasetId, String datasetVersion, String text, String url, String title,
		List<String> headingPath, List<String> sourceUrls, double similarity) {

	public RetrievedChunk {
		headingPath = List.copyOf(headingPath);
		sourceUrls = List.copyOf(sourceUrls);
	}

}
