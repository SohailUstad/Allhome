package com.allhome.colourcoats.ingestion;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, UUID> {

	List<KnowledgeChunkEntity> findByRunIdOrderByChunkId(UUID runId);

	long countByRunId(UUID runId);

}
