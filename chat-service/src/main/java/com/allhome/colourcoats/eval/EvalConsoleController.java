package com.allhome.colourcoats.eval;

import java.security.Principal;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.allhome.colourcoats.livemodel.LiveModelService;
import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptStatus;
import com.allhome.colourcoats.prompt.PromptVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Console pages for evals: start a run, watch it run case by case, read and compare reports, edit the cases. */
@Controller
@RequestMapping("/evals")
class EvalConsoleController {

	private final EvalService evals;

	private final PromptService prompts;

	private final LiveModelService liveModels;

	private final ZoneId zone;

	EvalConsoleController(EvalService evals, PromptService prompts, LiveModelService liveModels,
			@Value("${operator.time-zone:Asia/Kolkata}") ZoneId zone) {
		this.evals = evals;
		this.prompts = prompts;
		this.liveModels = liveModels;
		this.zone = zone;
	}

	@GetMapping
	String overview(@RequestParam(required = false) UUID prompt, Model model) {
		List<PromptVersion> versions = prompts.versions()
			.stream()
			.filter(v -> v.getStatus() == PromptStatus.ACTIVE || v.getStatus() == PromptStatus.DRAFT)
			.toList();
		Map<UUID, Integer> numbers = prompts.versions().stream()
			.collect(Collectors.toMap(PromptVersion::getId, PromptVersion::getVersionNumber));
		model.addAttribute("versions", versions);
		model.addAttribute("selectedPrompt", prompt);
		model.addAttribute("models", liveModels.allowed());
		model.addAttribute("liveModel", liveModels.current().model());
		model.addAttribute("runs", evals.recentRuns());
		model.addAttribute("versionNumbers", numbers);
		model.addAttribute("enabledCount", evals.enabledCases().size());
		model.addAttribute("criticalCount", evals.enabledCases().stream().filter(EvalCase::critical).count());
		model.addAttribute("minPassRate", evals.minPassRate());
		model.addAttribute("zone", zone);
		return "evals";
	}

	@PostMapping("/runs")
	String start(@RequestParam UUID prompt, @RequestParam(required = false) String model,
			@RequestParam(defaultValue = "false") boolean judge, @RequestParam(defaultValue = "false") boolean criticalOnly,
			Principal principal, RedirectAttributes redirect) {
		try {
			EvalRun run = evals.startRun(prompt, model, judge, criticalOnly, principal.getName());
			return "redirect:/evals/runs/" + run.getId();
		}
		catch (PromptExceptions.RuleViolation | PromptExceptions.NotFound ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/evals";
		}
	}

	@GetMapping("/runs/{id}")
	String report(@PathVariable UUID id, @RequestParam(required = false) UUID compare, Model model) {
		EvalRun run = evals.run(id);
		List<EvalResult> results = evals.results(id);
		Map<String, EvalResult> byCase = results.stream()
			.collect(Collectors.toMap(EvalResult::getCaseId, Function.identity()));
		List<EvalResult> ordered = run.getCaseIds().stream().filter(byCase::containsKey).map(byCase::get).toList();
		Map<String, String> before = compare == null ? Map.of()
				: evals.results(compare).stream()
					.collect(Collectors.toMap(EvalResult::getCaseId, r -> r.getDetail().label()));
		Map<String, long[]> categories = new TreeMap<>();
		for (EvalResult result : ordered) {
			long[] counts = categories.computeIfAbsent(result.getDetail().evalCase().category(), k -> new long[2]);
			counts[0] += result.isPassed() ? 1 : 0;
			counts[1]++;
		}
		Map<UUID, Integer> numbers = prompts.versions().stream()
			.collect(Collectors.toMap(PromptVersion::getId, PromptVersion::getVersionNumber));
		model.addAttribute("run", run);
		model.addAttribute("results", ordered);
		model.addAttribute("pending", run.getCaseIds().stream().filter(c -> !byCase.containsKey(c)).toList());
		model.addAttribute("passed", ordered.stream().filter(EvalResult::isPassed).count());
		model.addAttribute("criticalFailures", ordered.stream()
			.filter(r -> r.getDetail().evalCase().critical() && !r.isPassed())
			.map(EvalResult::getCaseId)
			.toList());
		model.addAttribute("categories", categories);
		model.addAttribute("compare", compare);
		model.addAttribute("before", before);
		model.addAttribute("otherRuns", evals.recentRuns().stream().filter(r -> !r.getId().equals(id) && r.isCompleted()).toList());
		model.addAttribute("versionNumbers", numbers);
		model.addAttribute("minPassRate", evals.minPassRate());
		model.addAttribute("zone", zone);
		return "eval-run";
	}

	/** Runs one case of a run; the report page calls this for each pending case in turn. */
	@PostMapping(path = "/runs/{id}/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	Map<String, Object> runCase(@PathVariable UUID id, @PathVariable String caseId) {
		EvalResult result = evals.runCase(id, caseId);
		var body = new LinkedHashMap<String, Object>();
		body.put("caseId", result.getCaseId());
		body.put("label", result.getDetail().label());
		body.put("completed", evals.run(id).isCompleted());
		return body;
	}

	// ---- Cases -------------------------------------------------------------------------------------------------

	@GetMapping("/cases")
	String cases(Model model) {
		model.addAttribute("cases", evals.cases());
		model.addAttribute("zone", zone);
		return "eval-cases";
	}

	@GetMapping("/cases/new")
	String newCase(Model model) {
		return editPage(model, new CaseForm("", "", "", false, "ZOHO_SALESIQ", "", "{\n  \"handoff\": false\n}", true),
				true, null);
	}

	@GetMapping("/cases/{id}")
	String editCase(@PathVariable String id, Model model) {
		StoredCase stored = evals.storedCase(id)
			.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
		return editPage(model, CaseForm.of(stored), false, null);
	}

	@PostMapping("/cases")
	String saveCase(CaseForm form, @RequestParam(defaultValue = "false") boolean isNew, Principal principal,
			Model model, RedirectAttributes redirect) {
		try {
			evals.saveCase(form.toCase(), form.isEnabled(), isNew, principal.getName());
			redirect.addFlashAttribute("notice", "Case '" + form.id() + "' saved.");
			return "redirect:/evals/cases";
		}
		catch (PromptExceptions.RuleViolation ex) {
			return editPage(model, form, isNew, ex.getMessage());
		}
	}

	private String editPage(Model model, CaseForm form, boolean isNew, String error) {
		model.addAttribute("form", form);
		model.addAttribute("isNew", isNew);
		model.addAttribute("error", error);
		return "eval-case-edit";
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail invalid(PromptExceptions.RuleViolation ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	org.springframework.http.ResponseEntity<ProblemDetail> status(org.springframework.web.server.ResponseStatusException ex) {
		return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
			.body(ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason()));
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail failed(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The case could not be run (AI provider or database): " + ex.getMessage());
	}

	/**
	 * The case form: turns one per line, expectations as JSON (the cases.json format).
	 */
	public record CaseForm(String id, String category, String description, Boolean critical, String channel, String turns,
			String expect, Boolean enabled) {

		public boolean isCritical() {
			return Boolean.TRUE.equals(critical);
		}

		public boolean isEnabled() {
			return Boolean.TRUE.equals(enabled);
		}

		static CaseForm of(StoredCase stored) {
			EvalCase c = stored.getDefinition();
			return new CaseForm(c.id(), c.category(), c.description(), c.critical(),
					c.channel() == null ? "ZOHO_SALESIQ" : c.channel(), String.join("\n", c.turns()),
					EvalEntities.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(c.expect()), stored.isEnabled());
		}

		EvalCase toCase() {
			EvalCase.Expect expectation;
			try {
				expectation = EvalEntities.JSON.readValue(expect == null || expect.isBlank() ? "{}" : expect,
						EvalCase.Expect.class);
			}
			catch (RuntimeException ex) {
				throw new PromptExceptions.RuleViolation("The expectations are not valid JSON: " + ex.getMessage());
			}
			List<String> lines = turns == null ? List.of()
					: Arrays.stream(turns.split("\\R")).map(String::strip).filter(t -> !t.isEmpty()).toList();
			return new EvalCase(id == null ? null : id.strip(), category == null ? null : category.strip(), description,
					isCritical(), channel, lines, expectation);
		}

	}

}
