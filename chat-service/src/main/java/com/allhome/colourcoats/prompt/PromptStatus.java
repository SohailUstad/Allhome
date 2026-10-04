package com.allhome.colourcoats.prompt;

/** Life cycle of a prompt version: drafts are edited, then activated; the version they replace is archived. */
public enum PromptStatus {

	/** Being edited; never used for visitors. */
	DRAFT,

	/** The version visitors get. Exactly one per prompt (enforced by the database). */
	ACTIVE,

	/** Was active before; can be activated again (rollback). */
	ARCHIVED,

	/** A draft the operator threw away; kept for the history. */
	DISCARDED

}
