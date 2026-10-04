package com.allhome.colourcoats.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * One retrieval chunk from a knowledge archive.
 *
 * @param id stable identifier, unique per dataset, version and chunk (see {@link KnowledgeArchiveReader})
 * @param chunkId identifier assigned by the extraction scripts
 * @param documentId page the chunk was extracted from
 * @param text chunk text, prefixed with the page title and heading path
 * @param url page URL
 * @param title page title, or {@code null}
 * @param fetchedAt when the page was crawled (ISO-8601 text as written by the scripts), or {@code null}
 * @param headingPath section headings the chunk belongs to, outermost first
 * @param sourceUrls page URLs with section anchors that the chunk text came from
 */
public record KnowledgeChunk(UUID id, String chunkId, String documentId, String text, String url, String title,
		String fetchedAt, List<String> headingPath, List<String> sourceUrls) {

	public KnowledgeChunk {
		headingPath = List.copyOf(headingPath);
		sourceUrls = List.copyOf(sourceUrls);
	}

}
