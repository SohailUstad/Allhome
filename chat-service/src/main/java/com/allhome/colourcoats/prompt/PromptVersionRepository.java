package com.allhome.colourcoats.prompt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PromptVersionRepository extends JpaRepository<PromptVersion, UUID> {

	Optional<PromptVersion> findByNameAndStatus(String name, PromptStatus status);

	List<PromptVersion> findByNameOrderByVersionNumberDesc(String name);

	/** A single-row lookup, used to check cheaply whether the cached active prompt is still the active one. */
	@Query("select v.id from PromptVersion v where v.name = :name and v.status = com.allhome.colourcoats.prompt.PromptStatus.ACTIVE")
	Optional<UUID> findActiveId(String name);

	@Query("select coalesce(max(v.versionNumber), 0) from PromptVersion v where v.name = :name")
	int maxVersionNumber(String name);

	/**
	 * Executes immediately (not at flush), so it can run before activating another version without ever having two
	 * active versions. Clears the persistence context: reload entities afterwards.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update PromptVersion v set v.status = com.allhome.colourcoats.prompt.PromptStatus.ARCHIVED "
			+ "where v.name = :name and v.status = com.allhome.colourcoats.prompt.PromptStatus.ACTIVE")
	int archiveActive(String name);

}
