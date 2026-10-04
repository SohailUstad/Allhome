package com.allhome.colourcoats.prompt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.allhome.colourcoats.chat.SystemPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The agent's system prompt, versioned in the database and edited in sections.
 *
 * <p>Every version is kept and exactly one is active. Changes always go into a draft; activating it makes it the
 * version visitors get from their next message on. The active prompt is cached in memory: activating a version in this
 * instance updates the cache at once, and other instances notice within {@code prompt.refresh-interval}.
 */
@Service
public class PromptService implements SystemPrompts {

	private static final Logger log = LoggerFactory.getLogger(PromptService.class);

	static final String SYSTEM_USER = "system";

	private final PromptVersionRepository versions;

	private final PromptProperties properties;

	private final ResourceLoader resources;

	private final TransactionTemplate transaction;

	private final Clock clock = Clock.systemUTC();

	private final AtomicReference<Cached> cache = new AtomicReference<>();

	// Not synchronized: SalesIQ answers on virtual threads, which a monitor held during a query would pin.
	private final ReentrantLock refreshLock = new ReentrantLock();

	// Looked up when needed: the gate (evals) itself depends on this service.
	private final ObjectProvider<ActivationGate> gate;

	PromptService(PromptVersionRepository versions, PromptProperties properties, ResourceLoader resources,
			PlatformTransactionManager transactionManager, ObjectProvider<ActivationGate> gate) {
		this.versions = versions;
		this.properties = properties;
		this.resources = resources;
		this.transaction = new TransactionTemplate(transactionManager);
		this.gate = gate;
	}

	/** Whether a draft may be activated now (always allowed when no gate is configured). */
	public ActivationGate.Verdict activationCheck(PromptVersion draft) {
		ActivationGate activationGate = gate.getIfAvailable();
		return activationGate == null ? new ActivationGate.Verdict(true, null) : activationGate.check(draft);
	}

	/** Stores the first version at startup, so the first visitor does not wait for it. */
	@EventListener(ApplicationReadyEvent.class)
	public void loadAtStartup() {
		try {
			SystemPrompt prompt = active();
			log.info("System prompt '{}' version {} is active", properties.name(), prompt.versionNumber());
		}
		catch (RuntimeException ex) {
			log.warn("Could not load the system prompt at startup: {}", ex.toString());
		}
	}

	// ---- The prompt visitors get -------------------------------------------------------------------------------

	/**
	 * The active prompt, from the in-memory cache. If the database cannot be reached, the last known prompt is used,
	 * or the git file when there is none yet, so visitors are always answered.
	 */
	@Override
	public SystemPrompt active() {
		Cached cached = cache.get();
		if (cached != null && cached.fresh(properties.refreshInterval().toNanos())) {
			return cached.prompt();
		}
		refreshLock.lock();
		try {
			cached = cache.get();
			if (cached != null && cached.fresh(properties.refreshInterval().toNanos())) {
				return cached.prompt();
			}
			SystemPrompt prompt = load(cached == null ? null : cached.prompt());
			cache.set(new Cached(prompt, System.nanoTime()));
			return prompt;
		}
		catch (RuntimeException ex) {
			if (cached != null) {
				log.warn("Could not check the active system prompt, keeping version {}: {}",
						cached.prompt().versionNumber(), ex.toString());
				cache.set(new Cached(cached.prompt(), System.nanoTime()));
				return cached.prompt();
			}
			log.error("Could not load the system prompt from the database, using {}: {}", properties.seed(),
					ex.toString());
			return new SystemPrompt(null, null, PromptDocument.assemble(PromptDocument.parse(seedText())));
		}
		finally {
			refreshLock.unlock();
		}
	}

	/** A single-row query when the active version is unchanged; the full version only when it changed. */
	private SystemPrompt load(SystemPrompt current) {
		UUID activeId = versions.findActiveId(properties.name()).orElse(null);
		if (activeId == null) {
			return toPrompt(seed());
		}
		if (current != null && activeId.equals(current.versionId())) {
			return current;
		}
		return toPrompt(versions.findById(activeId).orElseThrow(() -> new PromptExceptions.NotFound(activeId)));
	}

	/** Version 1 from the git file, stored and activated when the prompt has no active version yet. */
	private PromptVersion seed() {
		try {
			return transaction.execute(status -> versions.findByNameAndStatus(properties.name(), PromptStatus.ACTIVE)
				.orElseGet(() -> {
					var version = new PromptVersion(properties.name(), versions.maxVersionNumber(properties.name()) + 1,
							PromptDocument.parse(seedText()), null, "Initial version from " + properties.seed(),
							SYSTEM_USER, clock.instant());
					version.activate(SYSTEM_USER, clock.instant());
					PromptVersion saved = versions.saveAndFlush(version);
					log.info("Stored {} as version {} of system prompt '{}' and activated it", properties.seed(),
							saved.getVersionNumber(), properties.name());
					return saved;
				}));
		}
		catch (DataIntegrityViolationException ex) {
			// Another instance stored it at the same moment.
			return versions.findByNameAndStatus(properties.name(), PromptStatus.ACTIVE).orElseThrow(() -> ex);
		}
	}

	private String seedText() {
		try {
			return resources.getResource(properties.seed()).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read the system prompt file " + properties.seed(), ex);
		}
	}

	private static SystemPrompt toPrompt(PromptVersion version) {
		return new SystemPrompt(version.getId(), version.getVersionNumber(), version.getContent());
	}

	// ---- Versions, for the operator ----------------------------------------------------------------------------

	/** All versions of the prompt, newest first. */
	public List<PromptVersion> versions() {
		active(); // makes sure version 1 exists
		return versions.findByNameOrderByVersionNumberDesc(properties.name());
	}

	public PromptVersion version(UUID id) {
		return versions.findById(id)
			.filter(version -> version.getName().equals(properties.name()))
			.orElseThrow(() -> new PromptExceptions.NotFound(id));
	}

	public PromptVersion activeVersion() {
		UUID id = active().versionId();
		if (id == null) {
			throw new PromptExceptions.Conflict("The prompt database is unavailable");
		}
		return version(id);
	}

	public SectionLock lock(String key) {
		return properties.lock(key);
	}

	/**
	 * A new draft: the active version with the given sections changed.
	 * @param edits section key to its new text; sections not listed stay as they are
	 */
	public PromptVersion startDraft(Map<String, String> edits, String note, String by, boolean confirmProtected) {
		return transaction.execute(status -> {
			PromptVersion base = versions.findByNameAndStatus(properties.name(), PromptStatus.ACTIVE)
				.orElseGet(this::seed);
			List<PromptSection> sections = apply(base.getSections(), edits, confirmProtected, true);
			var draft = new PromptVersion(properties.name(), versions.maxVersionNumber(properties.name()) + 1, sections,
					base.getId(), note, by, clock.instant());
			PromptVersion saved = versions.saveAndFlush(draft);
			log.info("{} started draft version {} of system prompt '{}' (sections {})", by, saved.getVersionNumber(),
					properties.name(), edits.keySet());
			return saved;
		});
	}

	/** Changes sections of an existing draft. */
	public PromptVersion editDraft(UUID draftId, Map<String, String> edits, String note, String by,
			boolean confirmProtected) {
		return transaction.execute(status -> {
			PromptVersion draft = version(draftId);
			if (!draft.isDraft()) {
				throw new PromptExceptions.Conflict("Version " + draft.getVersionNumber() + " is "
						+ draft.getStatus().name().toLowerCase() + "; only drafts can be changed");
			}
			draft.replaceSections(apply(draft.getSections(), edits, confirmProtected, false), note, clock.instant());
			log.info("{} changed sections {} of draft version {}", by, edits.keySet(), draft.getVersionNumber());
			return draft;
		});
	}

	/**
	 * Makes a version the one visitors get. Drafts must have been started from the version that is active now, so a
	 * newer change is never overwritten silently; archived versions can always be activated again (rollback).
	 */
	public PromptVersion activate(UUID id, String by) {
		PromptVersion activated = transaction.execute(status -> {
			PromptVersion target = version(id);
			switch (target.getStatus()) {
				case ACTIVE -> {
					return target;
				}
				case DISCARDED -> throw new PromptExceptions.Conflict(
						"Version " + target.getVersionNumber() + " was discarded and cannot be activated");
				case DRAFT -> {
					UUID activeId = versions.findActiveId(properties.name()).orElse(null);
					if (activeId != null && !activeId.equals(target.getBaseVersionId())) {
						throw new PromptExceptions.Conflict("The active version changed after draft "
								+ target.getVersionNumber() + " was started; start a new draft from the active version");
					}
					ActivationGate.Verdict verdict = activationCheck(target);
					if (!verdict.allowed()) {
						throw new PromptExceptions.Conflict(verdict.message());
					}
				}
				case ARCHIVED -> {
				}
			}
			versions.archiveActive(properties.name());
			PromptVersion reloaded = version(id);
			reloaded.activate(by, clock.instant());
			return reloaded;
		});
		cache.set(new Cached(toPrompt(activated), System.nanoTime()));
		log.info("{} activated version {} of system prompt '{}'", by, activated.getVersionNumber(), properties.name());
		return activated;
	}

	/** Throws a draft away; it stays in the history. */
	public PromptVersion discard(UUID id, String by) {
		return transaction.execute(status -> {
			PromptVersion draft = version(id);
			if (!draft.isDraft()) {
				throw new PromptExceptions.Conflict("Only drafts can be discarded");
			}
			draft.discard(clock.instant());
			log.info("{} discarded draft version {} of system prompt '{}'", by, draft.getVersionNumber(),
					properties.name());
			return draft;
		});
	}

	/**
	 * Applies section edits after checking every rule: known sections only, locked sections unchanged, protected
	 * sections only when confirmed, no empty section, and the whole prompt within the size limit.
	 */
	public List<PromptSection> apply(List<PromptSection> sections, Map<String, String> edits, boolean confirmProtected,
			boolean requireChange) {
		for (String key : edits.keySet()) {
			if (sections.stream().noneMatch(section -> section.key().equals(key))) {
				throw new PromptExceptions.RuleViolation("There is no section '" + key + "'");
			}
		}
		List<PromptSection> result = new ArrayList<>(sections.size());
		List<String> changed = new ArrayList<>();
		List<String> unconfirmed = new ArrayList<>();
		for (PromptSection section : sections) {
			String edit = edits.get(section.key());
			if (edit == null || normalised(edit).equals(normalised(section.body()))) {
				result.add(section);
				continue;
			}
			if (edit.isBlank()) {
				throw new PromptExceptions.RuleViolation("Section '" + label(section) + "' cannot be empty");
			}
			switch (properties.lock(section.key())) {
				case LOCKED -> throw new PromptExceptions.RuleViolation(
						"Section '" + label(section) + "' is locked: the application depends on it");
				case PROTECTED -> {
					if (!confirmProtected) {
						unconfirmed.add(section.key());
					}
				}
				case NONE -> {
				}
			}
			changed.add(section.key());
			result.add(section.withBody(normalised(edit)));
		}
		if (!unconfirmed.isEmpty()) {
			throw new PromptExceptions.ConfirmationRequired(unconfirmed);
		}
		if (requireChange && changed.isEmpty()) {
			throw new PromptExceptions.RuleViolation("Nothing changed");
		}
		int length = PromptDocument.assemble(result).length();
		if (length > properties.maxLength()) {
			throw new PromptExceptions.RuleViolation(
					"The prompt would be " + length + " characters; the limit is " + properties.maxLength());
		}
		return result;
	}

	private static String normalised(String text) {
		return PromptDocument.normalise(text).strip();
	}

	public static String label(PromptSection section) {
		return Objects.requireNonNullElse(section.title(), "Introduction");
	}

	private record Cached(SystemPrompt prompt, long checkedAtNanos) {

		boolean fresh(long intervalNanos) {
			return System.nanoTime() - checkedAtNanos < intervalNanos;
		}

	}

}
