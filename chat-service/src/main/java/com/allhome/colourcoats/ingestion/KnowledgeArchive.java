package com.allhome.colourcoats.ingestion;

import java.util.List;

/**
 * A validated knowledge archive, as produced by {@code ingestion/package_knowledge.py}.
 *
 * @param datasetId dataset the chunks belong to, e.g. {@code colourcoats}
 * @param datasetVersion version label of this upload, e.g. {@code 2026-10-04}
 * @param source website the chunks were extracted from
 * @param chunks chunks in file order
 */
public record KnowledgeArchive(String datasetId, String datasetVersion, String source, List<KnowledgeChunk> chunks) {

	public KnowledgeArchive {
		chunks = List.copyOf(chunks);
	}

}
