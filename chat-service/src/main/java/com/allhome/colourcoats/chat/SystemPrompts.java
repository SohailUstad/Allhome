package com.allhome.colourcoats.chat;

import java.util.UUID;

/** Where the agent gets its system prompt for each message (the active version, see package {@code prompt}). */
@FunctionalInterface
public interface SystemPrompts {

    SystemPrompt active();

    /** @param versionId the stored version, or null when the prompt does not come from the database */
    record SystemPrompt(UUID versionId, Integer versionNumber, String content) {}

    /** A fixed prompt (tests, evals against the git file). */
    static SystemPrompts fixed(String content) {
        var prompt = new SystemPrompt(null, null, content);
        return () -> prompt;
    }
}
