package com.allhome.colourcoats.prompt;

/** Decides whether a draft may be activated (implemented by the evals: a passing eval run is required). */
public interface ActivationGate {

	Verdict check(PromptVersion draft);

	/** @param message what the operator sees: the result that allows it, or what is missing */
	record Verdict(boolean allowed, String message) {

	}

}
