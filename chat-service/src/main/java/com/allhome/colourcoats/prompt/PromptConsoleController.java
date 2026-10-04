package com.allhome.colourcoats.prompt;

import java.security.Principal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Prompt pages of the operator console: the prompt shown in sections, editing one section at a time into a draft,
 * activating a draft, rolling back, and the version history.
 */
@Controller
@RequestMapping("/prompts")
class PromptConsoleController {

	private final PromptService service;

	private final ZoneId zone;

	PromptConsoleController(PromptService service, @Value("${operator.time-zone:Asia/Kolkata}") ZoneId zone) {
		this.service = service;
		this.zone = zone;
	}

	@GetMapping
	String active() {
		return "redirect:/prompts/" + service.activeVersion().getId();
	}

	@GetMapping("/{id}")
	String version(@PathVariable UUID id, @RequestParam(required = false) String section, Model model) {
		PromptVersion version = find(id);
		PromptVersion base = version.isDraft() && version.getBaseVersionId() != null
				? service.version(version.getBaseVersionId()) : null;
		List<SectionRow> rows = version.getSections().stream().map(s -> row(s, base)).toList();
		SectionRow selected = rows.stream()
			.filter(row -> row.key().equals(section))
			.findFirst()
			.orElse(rows.getFirst());
		UUID activeId = service.active().versionId();

		model.addAttribute("version", version);
		model.addAttribute("sections", rows);
		model.addAttribute("selected", selected);
		model.addAttribute("base", base);
		model.addAttribute("changedCount", rows.stream().filter(SectionRow::changed).count());
		model.addAttribute("stale", version.isDraft() && !Objects.equals(version.getBaseVersionId(), activeId));
		model.addAttribute("gate", version.isDraft() ? service.activationCheck(version) : null);
		model.addAttribute("history", service.versions());
		model.addAttribute("activeId", activeId);
		model.addAttribute("zone", zone);
		return "prompt";
	}

	@GetMapping("/{id}/sections/{key}/edit")
	String editForm(@PathVariable UUID id, @PathVariable String key, Model model, RedirectAttributes redirect) {
		PromptVersion version = find(id);
		PromptSection section = version.section(key).orElseThrow(() -> notFound("section " + key));
		String refusal = editRefusal(version, key);
		if (refusal != null) {
			redirect.addFlashAttribute("error", refusal);
			return "redirect:/prompts/" + id + "?section=" + key;
		}
		return editPage(model, version, section, section.body(), null, false);
	}

	/** Saves into the draft being edited, or starts a new draft when editing the active version. */
	@PostMapping("/{id}/sections/{key}")
	String saveSection(@PathVariable UUID id, @PathVariable String key, @RequestParam String body,
			@RequestParam(required = false) String note,
			@RequestParam(defaultValue = "false") boolean confirmProtected, Principal principal, Model model,
			RedirectAttributes redirect) {
		PromptVersion version = find(id);
		PromptSection section = version.section(key).orElseThrow(() -> notFound("section " + key));
		String refusal = editRefusal(version, key);
		if (refusal != null) {
			redirect.addFlashAttribute("error", refusal);
			return "redirect:/prompts/" + id + "?section=" + key;
		}
		try {
			PromptVersion draft = version.isDraft()
					? service.editDraft(id, Map.of(key, body), note, principal.getName(), confirmProtected)
					: service.startDraft(Map.of(key, body), note, principal.getName(), confirmProtected);
			redirect.addFlashAttribute("notice", "Saved in draft version " + draft.getVersionNumber()
					+ ". Visitors keep getting the active version until you activate the draft.");
			return "redirect:/prompts/" + draft.getId() + "?section=" + key;
		}
		catch (PromptExceptions.ConfirmationRequired ex) {
			return editPage(model, version, section, body, ex.getMessage(), true);
		}
		catch (PromptExceptions.RuleViolation | PromptExceptions.Conflict ex) {
			return editPage(model, version, section, body, ex.getMessage(), false);
		}
	}

	@PostMapping("/{id}/activate")
	String activate(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
		try {
			PromptVersion activated = service.activate(id, principal.getName());
			redirect.addFlashAttribute("notice", "Version " + activated.getVersionNumber()
					+ " is now active: visitors get it from their next message.");
		}
		catch (PromptExceptions.Conflict ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/prompts/" + id;
	}

	@PostMapping("/{id}/discard")
	String discard(@PathVariable UUID id, Principal principal, RedirectAttributes redirect) {
		try {
			PromptVersion discarded = service.discard(id, principal.getName());
			redirect.addFlashAttribute("notice", "Draft version " + discarded.getVersionNumber() + " was discarded.");
			return "redirect:/prompts";
		}
		catch (PromptExceptions.Conflict ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/prompts/" + id;
		}
	}

	private String editPage(Model model, PromptVersion version, PromptSection section, String body, String error,
			boolean needsConfirmation) {
		model.addAttribute("version", version);
		model.addAttribute("section", row(section, null));
		model.addAttribute("body", body);
		model.addAttribute("error", error);
		model.addAttribute("needsConfirmation", needsConfirmation);
		return "prompt-section-edit";
	}

	/** Why this section of this version cannot be edited, or null when it can. */
	private String editRefusal(PromptVersion version, String key) {
		if (service.lock(key) == SectionLock.LOCKED) {
			return "This section is locked: the application depends on it.";
		}
		return switch (version.getStatus()) {
			case DRAFT, ACTIVE -> null;
			case ARCHIVED -> "Old versions are not edited. Activate it to roll back, or edit the active version.";
			case DISCARDED -> "This draft was discarded.";
		};
	}

	private SectionRow row(PromptSection section, PromptVersion base) {
		String before = base == null ? null : base.section(section.key()).map(PromptSection::body).orElse("");
		boolean changed = before != null && !PromptDocument.normalise(before).strip()
			.equals(PromptDocument.normalise(section.body()).strip());
		return new SectionRow(section.key(), PromptService.label(section), service.lock(section.key()), section.body(),
				changed, changed ? LineDiff.between(before, section.body()) : List.of());
	}

	private PromptVersion find(UUID id) {
		try {
			return service.version(id);
		}
		catch (PromptExceptions.NotFound ex) {
			throw notFound("prompt version " + id);
		}
	}

	private static ResponseStatusException notFound(String what) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown " + what);
	}

	/** A section as the page shows it; {@code diff} holds the changes from the draft's base version. */
	record SectionRow(String key, String title, SectionLock lock, String body, boolean changed,
			List<LineDiff.Line> diff) {

		public boolean locked() {
			return lock == SectionLock.LOCKED;
		}

		public boolean isProtected() {
			return lock == SectionLock.PROTECTED;
		}

	}

}
