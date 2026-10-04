package com.allhome.colourcoats.eval;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EvalResultRepository extends JpaRepository<EvalResult, UUID> {

	List<EvalResult> findByRunId(UUID runId);

	boolean existsByRunIdAndCaseId(UUID runId, String caseId);

	long countByRunIdAndPassedTrue(UUID runId);

}
