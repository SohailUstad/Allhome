package com.allhome.colourcoats.prompt.editor;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Console endpoints of the AI prompt editor. The "Ask AI" panel on the prompt page calls the JSON endpoints; accepting
 * and discarding are ordinary form posts that return to the prompt page.
 */
@Controller
@RequestMapping("/prompts")
class PromptEditorController {

	private final PromptEditorService editor;

	PromptEditorController(PromptEditorService editor) {
		this.editor = editor;
	}

	/** The models the operator can choose from, with whether the OpenAI key can use them. */
	@GetMapping(path = "/ai-editor/models", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	List<EditorModels.Choice> models() {
		return editor.modelChoices();
	}

	@GetMapping(path = "/{versionId}/ai-edits", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	List<Recent> recent(@PathVariable UUID versionId) {
		return editor.recent(versionId).stream().map(Recent::of).toList();
	}

	@GetMapping(path = "/ai-edits/{editId}", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	PromptEditorService.Proposal view(@PathVariable UUID editId) {
		return editor.view(editId);
	}

	/** Waits for the model (a reasoning model can take a minute or more); see the README for why it is synchronous. */
	@PostMapping(path = "/{versionId}/ai-edits", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	PromptEditorService.Proposal propose(@PathVariable UUID versionId, @RequestBody AskRequest request,
			Principal principal) {
		return editor.propose(versionId, request.instruction(), request.section(), request.model(), request.effort(),
				principal.getName());
	}

	@PostMapping(path = "/ai-edits/{editId}/refine", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	PromptEditorService.Proposal refine(@PathVariable UUID editId, @RequestBody RefineRequest request,
			Principal principal) {
		return editor.refine(editId, request.feedback(), request.model(), request.effort(), principal.getName());
	}

	@PostMapping("/ai-edits/{editId}/accept")
	String accept(@PathVariable UUID editId, @RequestParam(defaultValue = "false") boolean confirmProtected,
			Principal principal, RedirectAttributes redirect) {
		try {
			PromptVersion draft = editor.accept(editId, confirmProtected, principal.getName());
			redirect.addFlashAttribute("notice", "The AI's change is in draft version " + draft.getVersionNumber()
					+ ". Check it, try it, then activate it.");
			return "redirect:/prompts/" + draft.getId();
		}
		catch (PromptExceptions.RuleViolation | PromptExceptions.Conflict ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/prompts/" + editor.edit(editId).getVersionId();
		}
	}

	@PostMapping("/ai-edits/{editId}/discard")
	String discard(@PathVariable UUID editId, Principal principal, RedirectAttributes redirect) {
		UUID versionId = editor.edit(editId).getVersionId();
		try {
			editor.discard(editId, principal.getName());
			redirect.addFlashAttribute("notice", "The AI's proposal was discarded.");
		}
		catch (PromptExceptions.Conflict ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/prompts/" + versionId;
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail ruleViolation(PromptExceptions.RuleViolation ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail conflict(PromptExceptions.Conflict ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail notFound(PromptExceptions.NotFound ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	/**
	 * @param section a section key, or "any" to let the editor choose
	 * @param effort reasoning effort; ignored for standard models
	 */
	record AskRequest(String instruction, String section, String model, String effort) {

	}

	record RefineRequest(String feedback, String model, String effort) {

	}

	record Recent(UUID id, String status, String instruction, String model, String effort, String createdBy,
			Instant createdAt, boolean refinement) {

		static Recent of(PromptEdit edit) {
			return new Recent(edit.getId(), edit.getStatus().name(), edit.getInstruction(), edit.getModel(),
					edit.getReasoningEffort(), edit.getCreatedBy(), edit.getCreatedAt(), edit.getParentEditId() != null);
		}

	}

}
