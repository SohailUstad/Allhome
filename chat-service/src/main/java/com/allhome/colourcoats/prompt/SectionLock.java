package com.allhome.colourcoats.prompt;

/** How freely a prompt section may be changed (by the operator or the AI editor). */
public enum SectionLock {

	/** Editable. */
	NONE,

	/** Editable, but only with an explicit confirmation: a careless change here weakens a safety rule. */
	PROTECTED,

	/** Never editable: the code depends on it (input format, reply format, injection rule). */
	LOCKED

}
