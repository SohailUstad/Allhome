package com.allhome.colourcoats.prompt;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param name the prompt's name in {@code prompt_version}
 * @param seed the file stored as version 1 when no version exists yet, and used if the database is unreachable
 * @param refreshInterval how long a cached active prompt is used before checking whether another instance activated a
 * different version
 * @param maxLength maximum length of the assembled prompt, in characters
 * @param lockedSections section keys nobody may change
 * @param protectedSections section keys that need an explicit confirmation to change
 */
@ConfigurationProperties("prompt")
public record PromptProperties(@DefaultValue("sales-system") String name,
		@DefaultValue("classpath:prompts/sales-system.md") String seed, @DefaultValue("30s") Duration refreshInterval,
		@DefaultValue("50000") int maxLength, @DefaultValue({ "input", "output" }) List<String> lockedSections,
		@DefaultValue({ "strict-grounding", "persona", "intent" }) List<String> protectedSections) {

	public SectionLock lock(String key) {
		if (lockedSections.contains(key)) {
			return SectionLock.LOCKED;
		}
		return protectedSections.contains(key) ? SectionLock.PROTECTED : SectionLock.NONE;
	}

}
