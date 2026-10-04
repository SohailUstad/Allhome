package com.allhome.colourcoats.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

	Optional<IngestionRun> findByDatasetIdAndDatasetVersion(String datasetId, String datasetVersion);

	Optional<IngestionRun> findByDatasetIdAndActiveTrue(String datasetId);

	List<IngestionRun> findAllByOrderByIngestedAtDesc();

	List<IngestionRun> findByDatasetIdOrderByIngestedAtDesc(String datasetId);

	/**
	 * Executes immediately (not at flush), so it can run before activating another run without ever having two
	 * active runs. Clears the persistence context: reload entities afterwards.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update IngestionRun r set r.active = false where r.datasetId = :datasetId and r.active = true")
	int deactivateAll(String datasetId);

}
