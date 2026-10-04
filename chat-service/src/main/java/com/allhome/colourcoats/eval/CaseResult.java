package com.allhome.colourcoats.eval;

import java.util.List;

import com.allhome.colourcoats.chat.Lead;

/**
 * The outcome of one eval case: the conversation as it happened and every check.
 *
 * @param evalCase the case exactly as it was run (kept, so old reports stay correct after a case is edited)
 */
public record CaseResult(EvalCase evalCase, List<String> replies, boolean handoff, String handoffReason, Lead lead,
		List<String> sources, List<Check> checks, String error, long durationMs) {

	public static final String JUDGE_CHECK = "grounded (judge)";

	public static final String STYLE_PREFIX = "style: ";

	public record Check(String name, boolean passed, String detail) {

	}

	/** Every check passed, including the grounding judge and the style checks. */
	public boolean passed() {
		return error == null && checks.stream().allMatch(Check::passed);
	}

	/**
	 * The behaviour checks passed (handoff, persona, lead, content, safety). The judge and style checks are too noisy
	 * to decide on their own; they count as "review" in reports.
	 */
	public boolean behaviourPassed() {
		return error == null && checks.stream()
			.filter(check -> !check.name().equals(JUDGE_CHECK) && !check.name().startsWith(STYLE_PREFIX))
			.allMatch(Check::passed);
	}

	/** PASS, REVIEW (only the judge or a style check objected) or FAIL. */
	public String label() {
		return passed() ? "PASS" : behaviourPassed() ? "REVIEW" : "FAIL";
	}

}
