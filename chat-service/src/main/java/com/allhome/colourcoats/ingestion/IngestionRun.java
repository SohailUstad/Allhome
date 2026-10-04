package com.allhome.colourcoats.ingestion;

import java.time.Instant;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One successfully ingested version of a dataset. At most one run per dataset is active (searched). */
@Entity
@Table(name = "ingestion_run")
public class IngestionRun extends AssignedIdEntity {

	@Column(nullable = false, length = 100)
	private String datasetId;

	@Column(nullable = false, length = 100)
	private String datasetVersion;

	@Column(nullable = false)
	private String source;

	@Column(name = "archive_sha256", nullable = false, length = 64)
	private String archiveSha256;

	@Column(nullable = false)
	private int chunkCount;

	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false)
	private Instant ingestedAt;

	private Instant activatedAt;

	protected IngestionRun() {
	}

	IngestionRun(KnowledgeArchive archive, String archiveSha256, Instant ingestedAt) {
		super(UUID.randomUUID());
		this.datasetId = archive.datasetId();
		this.datasetVersion = archive.datasetVersion();
		this.source = archive.source();
		this.archiveSha256 = archiveSha256;
		this.chunkCount = archive.chunks().size();
		this.ingestedAt = ingestedAt;
	}

	/** Callers must first deactivate the dataset's current run; the database allows one active run per dataset. */
	void activate(Instant now) {
		this.active = true;
		this.activatedAt = now;
	}

	public String getDatasetId() {
		return datasetId;
	}

	public String getDatasetVersion() {
		return datasetVersion;
	}

	public String getSource() {
		return source;
	}

	public String getArchiveSha256() {
		return archiveSha256;
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getIngestedAt() {
		return ingestedAt;
	}

	public Instant getActivatedAt() {
		return activatedAt;
	}

}
