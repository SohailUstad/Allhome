package com.allhome.colourcoats.prompt;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator API for the agent's system prompt: versions, drafts, activation and rollback. */
@RestController
@RequestMapping("/api/prompts")
class PromptController {

	private final PromptService service;

	PromptController(PromptService service) {
		this.service = service;
	}

	@GetMapping
	List<PromptViews.Summary> list() {
		return service.versions().stream().map(PromptViews.Summary::of).toList();
	}

	@GetMapping("/active")
	PromptViews.Detail active() {
		return PromptViews.Detail.of(service.activeVersion(), service);
	}

	@GetMapping("/{id}")
	PromptViews.Detail get(@PathVariable UUID id) {
		return PromptViews.Detail.of(service.version(id), service);
	}

	/** A new draft from the active version with the given sections changed. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	PromptViews.Detail startDraft(@RequestBody PromptViews.DraftRequest request, Principal principal) {
		return PromptViews.Detail.of(service.startDraft(request.edits(), request.note(), principal.getName(),
				request.confirmed()), service);
	}

	@PatchMapping("/{id}")
	PromptViews.Detail editDraft(@PathVariable UUID id, @RequestBody PromptViews.DraftRequest request,
			Principal principal) {
		return PromptViews.Detail.of(service.editDraft(id, request.edits(), request.note(), principal.getName(),
				request.confirmed()), service);
	}

	@PostMapping("/{id}/activate")
	PromptViews.Summary activate(@PathVariable UUID id, Principal principal) {
		return PromptViews.Summary.of(service.activate(id, principal.getName()));
	}

	@PostMapping("/{id}/discard")
	PromptViews.Summary discard(@PathVariable UUID id, Principal principal) {
		return PromptViews.Summary.of(service.discard(id, principal.getName()));
	}

}
