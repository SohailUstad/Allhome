package com.allhome.colourcoats.ingestion;

import java.time.Instant;
import java.util.UUID;

/** A stored version as shown to operators, e.g. to pick a run to roll back to. */
public record IngestionRunSummary(UUID runId, String datasetId, String datasetVersion, String source, int chunkCount,
		boolean active, String archiveSha256, Instant ingestedAt, Instant activatedAt) {

	static IngestionRunSummary of(IngestionRun run) {
		return new IngestionRunSummary(run.getId(), run.getDatasetId(), run.getDatasetVersion(), run.getSource(),
				run.getChunkCount(), run.isActive(), run.getArchiveSha256(), run.getIngestedAt(), run.getActivatedAt());
	}

}
