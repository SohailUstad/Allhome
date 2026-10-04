package com.allhome.colourcoats.eval;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {

	List<EvalRun> findTop30ByOrderByCreatedAtDesc();

	List<EvalRun> findByPromptVersionIdAndStatusOrderByCreatedAtDesc(UUID promptVersionId, String status);

}
