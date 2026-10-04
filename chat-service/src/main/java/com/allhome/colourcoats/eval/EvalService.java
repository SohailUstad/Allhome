package com.allhome.colourcoats.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.allhome.colourcoats.chat.Lead;
import com.allhome.colourcoats.chat.SystemPrompts;
import com.allhome.colourcoats.livemodel.LiveModelService;
import com.allhome.colourcoats.prompt.ActivationGate;
import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Eval cases (editable), eval runs and their results, and the rule that a prompt draft can only be activated after a
 * full eval run on it, with the live model, passed at least {@code evals.activation-min-pass-rate} of the cases.
 */
@Service
public class EvalService implements ActivationGate {

	private static final Logger log = LoggerFactory.getLogger(EvalService.class);

	private static final Pattern CASE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,98}");

	static final Set<String> LEAD_FIELDS = Set.of("name", "phone", "email", "city", "projectType", "spaces",
			"finishInterest", "areaSize", "timeline", "budget", "callbackTime");

	private final StoredCaseRepository cases;

	private final EvalRunRepository runs;

	private final EvalResultRepository results;

	private final EvalRunner runner;

	private final PromptService prompts;

	private final LiveModelService liveModels;

	private final Resource seedFile;

	private final double minPassRate;

	EvalService(StoredCaseRepository cases, EvalRunRepository runs, EvalResultRepository results, EvalRunner runner,
			PromptService prompts, LiveModelService liveModels,
			@Value("classpath:evals/cases.json") Resource seedFile,
			@Value("${evals.activation-min-pass-rate:0.9}") double minPassRate) {
		this.cases = cases;
		this.runs = runs;
		this.results = results;
		this.runner = runner;
		this.prompts = prompts;
		this.liveModels = liveModels;
		this.seedFile = seedFile;
		this.minPassRate = minPassRate;
	}

	/** Loads evals/cases.json the first time, so the console starts with the cases from git. */
	@EventListener(ApplicationReadyEvent.class)
	public void seedCases() {
		try {
			if (cases.count() > 0) {
				return;
			}
			List<EvalCase> seed = EvalEntities.JSON.readValue(seedFile.getInputStream(), new TypeReference<>() {
			});
			cases.saveAll(seed.stream().map(c -> new StoredCase(c, true, "system", Instant.now())).toList());
			log.info("Stored {} eval cases from {}", seed.size(), seedFile);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			log.warn("Could not store the eval cases: {}", ex.toString());
		}
	}

	// ---- Cases -------------------------------------------------------------------------------------------------

	public List<StoredCase> cases() {
		return cases.findAllByOrderByIdAsc();
	}

	public List<EvalCase> enabledCases() {
		return cases().stream().filter(StoredCase::isEnabled).map(StoredCase::getDefinition).toList();
	}

	public Optional<StoredCase> storedCase(String id) {
		return cases.findById(id);
	}

	/** Creates or updates a case after checking it can actually be run. */
	public StoredCase saveCase(EvalCase definition, boolean enabled, boolean isNew, String by) {
		validate(definition);
		Optional<StoredCase> existing = cases.findById(definition.id());
		if (isNew && existing.isPresent()) {
			throw new PromptExceptions.RuleViolation("A case with id '" + definition.id() + "' already exists");
		}
		StoredCase stored = existing.orElseGet(() -> new StoredCase(definition, enabled, by, Instant.now()));
		stored.update(definition, enabled, by, Instant.now());
		return cases.saveAndFlush(stored);
	}

	static void validate(EvalCase c) {
		if (c.id() == null || !CASE_ID.matcher(c.id()).matches()) {
			throw new PromptExceptions.RuleViolation("The id must be 2-100 lowercase letters, digits or dashes");
		}
		if (c.category() == null || c.category().isBlank()) {
			throw new PromptExceptions.RuleViolation("Give the case a category");
		}
		if (c.turns() == null || c.turns().isEmpty() || c.turns().stream().anyMatch(t -> t == null || t.isBlank())) {
			throw new PromptExceptions.RuleViolation("Give at least one visitor message, none empty");
		}
		try {
			c.resolvedChannel();
		}
		catch (IllegalArgumentException ex) {
			throw new PromptExceptions.RuleViolation("Unknown channel '" + c.channel() + "'");
		}
		EvalCase.Expect e = c.expect();
		if (e == null) {
			throw new PromptExceptions.RuleViolation("Give at least one expectation");
		}
		for (List<String> patterns : java.util.Arrays.asList(e.mustNotMatch(), e.finalMustNotMatch())) {
			for (String regex : patterns == null ? List.<String>of() : patterns) {
				try {
					Pattern.compile(regex);
				}
				catch (PatternSyntaxException ex) {
					throw new PromptExceptions.RuleViolation("Invalid pattern /" + regex + "/: " + ex.getDescription());
				}
			}
		}
		if (e.persona() != null && !Lead.PERSONAS.contains(e.persona())) {
			throw new PromptExceptions.RuleViolation("Unknown persona '" + e.persona() + "'");
		}
		if (e.intent() != null && !Lead.INTENTS.contains(e.intent())) {
			throw new PromptExceptions.RuleViolation("Unknown intent '" + e.intent() + "'");
		}
		if (e.leadStatus() != null && !List.of("NEW", "ENGAGED", "QUALIFIED").contains(e.leadStatus())) {
			throw new PromptExceptions.RuleViolation("Unknown lead status '" + e.leadStatus() + "'");
		}
		if (e.lead() != null && !LEAD_FIELDS.containsAll(e.lead().keySet())) {
			throw new PromptExceptions.RuleViolation("Lead fields must be among " + LEAD_FIELDS);
		}
	}

	// ---- Runs --------------------------------------------------------------------------------------------------

	/**
	 * Plans a run; the console then runs its cases one by one ({@link #runCase}), so no request takes longer than one
	 * case (Cloud Run only gives the app CPU during requests).
	 * @param criticalOnly true for a quick run of the critical cases (cannot allow an activation)
	 */
	public EvalRun startRun(UUID promptVersionId, String model, boolean judge, boolean criticalOnly, String by) {
		prompts.version(promptVersionId);
		String chosen = model == null || model.isBlank() ? liveModels.current().model() : model;
		if (liveModels.allowed().stream().noneMatch(allowed -> allowed.id().equals(chosen))) {
			throw new PromptExceptions.RuleViolation("Model '" + chosen + "' is not allowed for live conversations");
		}
		List<String> ids = enabledCases().stream()
			.filter(c -> !criticalOnly || c.critical())
			.map(EvalCase::id)
			.toList();
		if (ids.isEmpty()) {
			throw new PromptExceptions.RuleViolation("There are no enabled cases to run");
		}
		EvalRun run = runs.saveAndFlush(new EvalRun(promptVersionId, chosen, judge, !criticalOnly, ids, by, Instant.now()));
		log.info("{} started eval run {} ({} cases, prompt {}, model {})", by, run.getId(), ids.size(), promptVersionId,
				chosen);
		return run;
	}

	/** Runs one case of a run (once; a repeated call returns the stored result) and completes the run after the last. */
	public EvalResult runCase(UUID runId, String caseId) {
		EvalRun run = run(runId);
		if (!run.getCaseIds().contains(caseId)) {
			throw new PromptExceptions.RuleViolation("Case '" + caseId + "' is not part of this run");
		}
		Optional<EvalResult> done = results.findByRunId(runId).stream().filter(r -> r.getCaseId().equals(caseId)).findFirst();
		if (done.isPresent()) {
			return done.get();
		}
		EvalCase definition = cases.findById(caseId)
			.map(StoredCase::getDefinition)
			.orElseThrow(() -> new PromptExceptions.RuleViolation("Case '" + caseId + "' no longer exists"));
		PromptVersion version = prompts.version(run.getPromptVersionId());
		CaseResult outcome = runner.run(definition,
				new SystemPrompts.SystemPrompt(version.getId(), version.getVersionNumber(), version.getContent()),
				run.getModel(), run.isJudge());
		EvalResult saved;
		try {
			saved = results.saveAndFlush(new EvalResult(runId, outcome, Instant.now()));
		}
		catch (DataIntegrityViolationException ex) { // the same case was run twice at once
			return results.findByRunId(runId).stream().filter(r -> r.getCaseId().equals(caseId)).findFirst().orElseThrow();
		}
		if (results.findByRunId(runId).size() >= run.getCaseIds().size()) {
			EvalRun reloaded = run(runId);
			if (!reloaded.isCompleted()) {
				reloaded.complete((int) results.countByRunIdAndPassedTrue(runId), Instant.now());
				runs.saveAndFlush(reloaded);
				log.info("Eval run {} completed: {}/{} cases passed", runId, reloaded.getPassedCases(),
						reloaded.getCaseIds().size());
			}
		}
		return saved;
	}

	public EvalRun run(UUID runId) {
		return runs.findById(runId).orElseThrow(() -> new PromptExceptions.RuleViolation("Unknown eval run " + runId));
	}

	public List<EvalResult> results(UUID runId) {
		return results.findByRunId(runId);
	}

	public List<EvalRun> recentRuns() {
		return runs.findTop30ByOrderByCreatedAtDesc();
	}

	public double minPassRate() {
		return minPassRate;
	}

	// ---- Activation gate ---------------------------------------------------------------------------------------

	/**
	 * A draft may be activated when its latest completed full run with the live model passed at least the threshold.
	 * A threshold of 0 switches the gate off.
	 */
	@Override
	public Verdict check(PromptVersion draft) {
		if (minPassRate <= 0) {
			return new Verdict(true, null);
		}
		String live = liveModels.current().model();
		String needed = Math.round(minPassRate * 100) + "%";
		Optional<EvalRun> latest = runs
			.findByPromptVersionIdAndStatusOrderByCreatedAtDesc(draft.getId(), "COMPLETED")
			.stream()
			.filter(run -> run.isFullRun() && run.getModel().equals(live))
			.findFirst();
		if (latest.isEmpty()) {
			return new Verdict(false, "Run the full evals on version " + draft.getVersionNumber() + " with the live model "
					+ live + " first; at least " + needed + " of the cases must pass.");
		}
		EvalRun run = latest.get();
		String result = run.getPassedCases() + "/" + run.getCaseIds().size() + " cases ("
				+ Math.round(run.passRate() * 100) + "%)";
		return run.passRate() >= minPassRate ? new Verdict(true, "Latest full eval: " + result + " passed; " + needed + " needed.")
				: new Verdict(false, "Latest full eval: only " + result + " passed; " + needed + " needed to activate.");
	}

}
