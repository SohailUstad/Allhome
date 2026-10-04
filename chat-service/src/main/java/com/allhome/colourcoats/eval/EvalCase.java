package com.allhome.colourcoats.eval;

import java.util.List;
import java.util.Map;

import com.allhome.colourcoats.chat.Channel;

/**
 * One scripted conversation and what the agent must do in it (the format of {@code evals/cases.json}).
 *
 * @param channel WEB_CHAT, ZOHO_SALESIQ (default) or INSTAGRAM
 * @param turns the visitor's messages, in order
 */
public record EvalCase(String id, String category, String description, boolean critical, String channel,
		List<String> turns, Expect expect) {

	public Channel resolvedChannel() {
		return channel == null || channel.isBlank() ? Channel.ZOHO_SALESIQ : Channel.valueOf(channel);
	}

	/** Every field is optional; only the ones given are checked. */
	public record Expect(List<String> sourcesInclude, Boolean handoff, String persona, String intent, String leadStatus,
			Map<String, String> lead, List<String> mustMentionAny, List<String> mustNotMatch,
			List<String> finalMustNotMatch, Integer maxReplyChars, Boolean grounded, Integer minMentions) {

	}

}
