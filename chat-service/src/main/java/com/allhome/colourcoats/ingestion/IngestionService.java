package com.allhome.colourcoats.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores knowledge archives as versioned ingestion runs and controls which version is active.
 *
 * <p>Every version is kept; exactly one run per dataset is active. A new version becomes active as soon as it is
 * stored. Uploading an existing version again is a no-op when the content is identical and rejected otherwise.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private final IngestionRunRepository runs;

	private final KnowledgeChunkRepository chunks;

	private final ChunkEmbedder embedder;

	private final TransactionTemplate transaction;

	IngestionService(IngestionRunRepository runs, KnowledgeChunkRepository chunks, ChunkEmbedder embedder,
			PlatformTransactionManager transactionManager) {
		this.runs = runs;
		this.chunks = chunks;
		this.embedder = embedder;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * @param archiveSha256 SHA-256 (hex) of the uploaded ZIP, used to recognise a repeated upload
	 * @throws VersionConflictException if this version already exists with different content
	 */
	public IngestionResult ingest(KnowledgeArchive archive, String archiveSha256) {
		var existing = runs.findByDatasetIdAndDatasetVersion(archive.datasetId(), archive.datasetVersion());
		if (existing.isPresent()) {
			return unchangedOrConflict(existing.get(), archiveSha256);
		}

		long started = System.nanoTime();
		// Embedding calls the model over the network; keep it outside the database transaction.
		List<float[]> embeddings = embedder.embed(archive.chunks());
		IngestionRun run;
		try {
			run = transaction.execute(status -> store(archive, archiveSha256, embeddings));
		}
		catch (DataIntegrityViolationException ex) {
			// The same version was stored concurrently between the check above and this insert.
			return runs.findByDatasetIdAndDatasetVersion(archive.datasetId(), archive.datasetVersion())
				.map(concurrent -> unchangedOrConflict(concurrent, archiveSha256))
				.orElseThrow(() -> ex);
		}
		log.info("Ingested dataset {} version {}: {} chunks in {} ms, run {} is now active", run.getDatasetId(),
				run.getDatasetVersion(), run.getChunkCount(), (System.nanoTime() - started) / 1_000_000, run.getId());
		return IngestionResult.of(IngestionResult.Status.INGESTED, run);
	}

	/**
	 * Makes an existing run the active version of its dataset (for example to roll back to an earlier version).
	 * @throws IngestionRunNotFoundException if the run does not exist
	 */
	public IngestionResult activate(UUID runId) {
		IngestionRun run = transaction.execute(status -> {
			IngestionRun target = runs.findById(runId).orElseThrow(() -> new IngestionRunNotFoundException(runId));
			if (target.isActive()) {
				return target;
			}
			String datasetId = target.getDatasetId();
			runs.deactivateAll(datasetId);
			IngestionRun reloaded = runs.findById(runId).orElseThrow(() -> new IngestionRunNotFoundException(runId));
			reloaded.activate(Instant.now());
			return reloaded;
		});
		log.info("Activated dataset {} version {} (run {})", run.getDatasetId(), run.getDatasetVersion(), run.getId());
		return IngestionResult.of(IngestionResult.Status.ACTIVATED, run);
	}

	/** Stored versions, newest first; all datasets when {@code datasetId} is null. */
	public List<IngestionRunSummary> list(String datasetId) {
		List<IngestionRun> found = datasetId == null ? runs.findAllByOrderByIngestedAtDesc()
				: runs.findByDatasetIdOrderByIngestedAtDesc(datasetId);
		return found.stream().map(IngestionRunSummary::of).toList();
	}

	private IngestionRun store(KnowledgeArchive archive, String archiveSha256, List<float[]> embeddings) {
		runs.deactivateAll(archive.datasetId());
		IngestionRun run = new IngestionRun(archive, archiveSha256, Instant.now());
		run.activate(run.getIngestedAt());
		runs.save(run);
		List<KnowledgeChunkEntity> entities = new ArrayList<>(archive.chunks().size());
		for (int i = 0; i < archive.chunks().size(); i++) {
			entities.add(new KnowledgeChunkEntity(run, archive.chunks().get(i), embeddings.get(i)));
		}
		chunks.saveAll(entities);
		return run;
	}

	private static IngestionResult unchangedOrConflict(IngestionRun existing, String archiveSha256) {
		if (!existing.getArchiveSha256().equals(archiveSha256)) {
			throw new VersionConflictException(existing.getDatasetId(), existing.getDatasetVersion());
		}
		return IngestionResult.of(IngestionResult.Status.ALREADY_INGESTED, existing);
	}

}
