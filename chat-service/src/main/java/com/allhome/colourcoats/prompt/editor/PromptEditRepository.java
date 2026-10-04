package com.allhome.colourcoats.prompt.editor;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PromptEditRepository extends JpaRepository<PromptEdit, UUID> {

	List<PromptEdit> findTop10ByVersionIdOrderByCreatedAtDesc(UUID versionId);

}
