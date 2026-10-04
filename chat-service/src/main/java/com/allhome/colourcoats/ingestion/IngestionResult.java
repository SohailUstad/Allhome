package com.allhome.colourcoats.ingestion;

import java.util.UUID;

/**
 * Outcome of an ingestion or activation.
 *
 * @param status what happened
 * @param runId the run that was stored, found or activated
 */
public record IngestionResult(Status status, UUID runId, String datasetId, String datasetVersion, int chunkCount,
		boolean active) {

	public enum Status {

		/** A new version was stored and is now active. */
		INGESTED,

		/** The same version with the same content was already stored; nothing changed. */
		ALREADY_INGESTED,

		/** An existing version was made active. */
		ACTIVATED

	}

	static IngestionResult of(Status status, IngestionRun run) {
		return new IngestionResult(status, run.getId(), run.getDatasetId(), run.getDatasetVersion(), run.getChunkCount(),
				run.isActive());
	}

}
