package com.allhome.colourcoats.prompt;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties the prompt tables between tests without touching conversations. */
public final class PromptTables {

	private PromptTables() {
	}

	public static void reset(JdbcTemplate jdbc) {
		jdbc.update("DELETE FROM prompt_edit");
		jdbc.update("UPDATE chat_message SET prompt_version_id = NULL");
		jdbc.update("DELETE FROM prompt_version");
	}

}
