package com.allhome.colourcoats.ingestion;

/** A dataset version label was uploaded again with different content; a version always means one exact content. */
public class VersionConflictException extends RuntimeException {

	public VersionConflictException(String datasetId, String datasetVersion) {
		super("Version '%s' of dataset '%s' already exists with different content; upload it under a new version"
			.formatted(datasetVersion, datasetId));
	}

}
