package com.allhome.colourcoats.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.livemodel.LiveModelService;
import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Eval cases, runs and the activation gate against the real database (the shared fake model answers every turn). */
@IntegrationTest
class EvalServiceTests {

	@Autowired
	EvalService evals;

	@Autowired
	StoredCaseRepository cases;

	@Autowired
	EvalRunRepository runs;

	@Autowired
	EvalResultRepository results;

	@Autowired
	EvalRunner runner;

	@Autowired
	PromptService prompts;

	@Autowired
	LiveModelService liveModels;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void reset() {
		PromptTables.reset(jdbc);
	}

	@Test
	void casesFromGitAreLoadedOnFirstStart() {
		assertThat(evals.cases()).hasSizeGreaterThanOrEqualTo(36);
		assertThat(evals.storedCase("pricing-handoff")).isPresent();
	}

	@Test
	void editedCasesAreValidatedAndKept() {
		var c = new EvalCase("operator-made-case", "conversation", "Made in the console", false, "INSTAGRAM",
				List.of("Hi", "Price?"), new EvalCase.Expect(null, false, null, null, null, null, null,
						List.of("(?i)rs\\.?\\s*\\d"), null, null, null, null));
		try {
			var saved = evals.saveCase(c, true, true, "op");
			assertThat(saved.getDefinition().turns()).containsExactly("Hi", "Price?");
			assertThatThrownBy(() -> evals.saveCase(c, true, true, "op")).hasMessageContaining("already exists");
			evals.saveCase(c, false, false, "op2"); // disable
			assertThat(evals.storedCase("operator-made-case").orElseThrow().isEnabled()).isFalse();
			assertThat(evals.enabledCases()).noneMatch(e -> e.id().equals("operator-made-case"));
		}
		finally {
			cases.deleteById("operator-made-case");
		}

		assertThatThrownBy(() -> evals.saveCase(withExpect("Bad Id!", c.expect()), true, true, "op"))
			.isInstanceOf(PromptExceptions.RuleViolation.class);
		assertThatThrownBy(() -> evals.saveCase(withExpect("bad-regex", new EvalCase.Expect(null, null, null, null, null,
				null, null, List.of("(unclosed"), null, null, null, null)), true, true, "op"))
			.hasMessageContaining("Invalid pattern");
		assertThatThrownBy(() -> evals.saveCase(withExpect("bad-persona", new EvalCase.Expect(null, null, "ALIEN", null,
				null, null, null, null, null, null, null, null)), true, true, "op"))
			.hasMessageContaining("Unknown persona");
		assertThatThrownBy(() -> evals.saveCase(withExpect("bad-field", new EvalCase.Expect(null, null, null, null, null,
				Map.of("shoeSize", "9"), null, null, null, null, null, null)), true, true, "op"))
			.hasMessageContaining("Lead fields");
	}

	@Test
	void aRunExecutesCaseByCaseStoresResultsAndCompletes() {
		var active = prompts.active();
		int conversations = jdbc.queryForObject("SELECT count(*) FROM chat_conversation", Integer.class);
		var run = evals.startRun(active.versionId(), null, false, true, "op");

		assertThat(run.isFullRun()).isFalse();
		assertThat(run.getModel()).isEqualTo(liveModels.current().model());
		assertThat(run.getCaseIds()).isNotEmpty().allMatch(id -> evals.storedCase(id).orElseThrow().getDefinition().critical());

		for (String caseId : run.getCaseIds()) {
			var result = evals.runCase(run.getId(), caseId);
			assertThat(result.getDetail().replies()).hasSize(result.getDetail().evalCase().turns().size());
			assertThat(result.getDetail().evalCase().id()).isEqualTo(caseId);
		}
		var again = evals.runCase(run.getId(), run.getCaseIds().getFirst()); // stored once
		assertThat(results.findByRunId(run.getId())).hasSize(run.getCaseIds().size());
		assertThat(again.getId()).isEqualTo(results.findByRunId(run.getId()).stream()
			.filter(r -> r.getCaseId().equals(run.getCaseIds().getFirst())).findFirst().orElseThrow().getId());

		var completed = evals.run(run.getId());
		assertThat(completed.isCompleted()).isTrue();
		assertThat(completed.getPassedCases()).isEqualTo((int) results.countByRunIdAndPassedTrue(run.getId()));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_conversation", Integer.class))
			.isEqualTo(conversations); // eval traffic is never stored as conversations

		assertThatThrownBy(() -> evals.startRun(active.versionId(), "o3", false, true, "op"))
			.hasMessageContaining("not allowed");
		assertThatThrownBy(() -> evals.runCase(run.getId(), "not-in-run")).hasMessageContaining("not part");
	}

	@Test
	void draftActivationNeedsAFullPassingRunWithTheLiveModel() {
		var gated = new EvalService(cases, runs, results, runner, prompts, liveModels,
				new ClassPathResource("evals/cases.json"), 0.9);
		var draft = prompts.startDraft(Map.of("complaints", "Gated change."), null, "op", false);
		String live = liveModels.current().model();

		assertThat(gated.check(draft).allowed()).isFalse();
		assertThat(gated.check(draft).message()).contains("Run the full evals");

		completedRun(draft.getId(), live, true, 10, 8); // 80%
		assertThat(gated.check(draft).allowed()).isFalse();
		assertThat(gated.check(draft).message()).contains("8/10", "90%");

		completedRun(draft.getId(), "gpt-4.1", true, 10, 10); // another model does not count
		completedRun(draft.getId(), live, false, 10, 10); // nor does a critical-only run
		assertThat(gated.check(draft).allowed()).isFalse();

		completedRun(draft.getId(), live, true, 10, 9); // 90%
		assertThat(gated.check(draft).allowed()).isTrue();
		assertThat(gated.check(draft).message()).contains("9/10");

		var off = new EvalService(cases, runs, results, runner, prompts, liveModels,
				new ClassPathResource("evals/cases.json"), 0);
		assertThat(off.check(draft).allowed()).isTrue();
	}

	private void completedRun(java.util.UUID prompt, String model, boolean full, int total, int passed) {
		var run = new EvalRun(prompt, model, false, full, IntStream.range(0, total).mapToObj(i -> "c" + i).toList(), "op",
				Instant.now());
		run.complete(passed, Instant.now());
		runs.saveAndFlush(run);
	}

	private static EvalCase withExpect(String id, EvalCase.Expect expect) {
		return new EvalCase(id, "conversation", null, false, null, List.of("Hi"), expect);
	}

}
