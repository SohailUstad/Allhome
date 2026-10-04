package com.allhome.colourcoats.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.Lead;
import com.allhome.colourcoats.chat.SystemPrompts;
import com.allhome.colourcoats.eval.CaseResult.Check;
import com.allhome.colourcoats.flowlog.TraceContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Runs one eval case against the real agent (real retrieval, real model) with a given prompt and model, and checks
 * every reply. Uses the agent's no-storage path: eval conversations never become conversations or leads.
 */
@Component
public class EvalRunner {

	/** "Short, human, one question at a time" applies to every reply of every case. */
	static final int MAX_WORDS = 60;

	static final int MAX_QUESTIONS = 1;

	private final ChatService chat;

	private final ChatClient judge;

	private final JdbcClient jdbc;

	private final String judgeModel;

	private final String judgeInstructions;

	EvalRunner(ChatService chat, ChatClient.Builder chatClientBuilder, JdbcClient jdbc,
			@Value("${evals.judge-model:gpt-4.1}") String judgeModel,
			@Value("classpath:evals/grounding-judge.md") Resource judgeInstructions) {
		this.chat = chat;
		this.judge = chatClientBuilder.build();
		this.jdbc = jdbc;
		this.judgeModel = judgeModel;
		try {
			this.judgeInstructions = judgeInstructions.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** @param useJudge whether the LLM judge checks grounding (cases marked "grounded") */
	public CaseResult run(EvalCase c, SystemPrompts.SystemPrompt prompt, String model, boolean useJudge) {
		long started = System.nanoTime();
		List<String> replies = new ArrayList<>();
		List<ChatRepository.StoredMessage> history = new ArrayList<>();
		Lead lead = Lead.EMPTY;
		ChatService.Turn last = null;
		try (var scope = TraceContext.open()) {
			TraceContext.current().trafficKind = "eval";
			TraceContext.current().evalCase = c.id();
			for (String turn : c.turns()) {
				last = chat.preview(prompt, model, List.copyOf(history), lead, turn, c.resolvedChannel());
				replies.add(last.reply());
				history.add(new ChatRepository.StoredMessage("USER", turn, null));
				history.add(new ChatRepository.StoredMessage("ASSISTANT", last.reply(), null));
				lead = last.lead();
			}
		}
		catch (RuntimeException ex) {
			return new CaseResult(c, replies, false, null, lead, List.of(), List.of(),
					ex.getClass().getSimpleName() + ": " + ex.getMessage(), elapsed(started));
		}
		List<String> sources = last.sources().stream().map(s -> String.valueOf(s.url())).toList();
		List<Check> checks = checks(c, replies, last, lead, sources);
		if (Boolean.TRUE.equals(c.expect().grounded()) && useJudge) {
			checks.add(judge(last));
		}
		return new CaseResult(c, replies, last.handoff(), last.handoffReason(), lead, sources, checks, null,
				elapsed(started));
	}

	static List<Check> checks(EvalCase c, List<String> replies, ChatService.Turn last, Lead lead,
			List<String> sources) {
		EvalCase.Expect e = c.expect();
		String finalReply = last.reply();
		List<Check> checks = new ArrayList<>();
		if (e.sourcesInclude() != null) {
			for (String expected : e.sourcesInclude()) {
				checks.add(new Check("retrieval " + expected, sources.stream().anyMatch(u -> u.contains(expected)),
						"sources=" + sources));
			}
		}
		if (e.handoff() != null) {
			checks.add(new Check("handoff=" + e.handoff(), e.handoff() == last.handoff(),
					"got " + last.handoff() + (last.handoffReason() == null ? "" : " (" + last.handoffReason() + ")")));
		}
		if (e.persona() != null) {
			checks.add(new Check("persona=" + e.persona(), e.persona().equals(lead.persona()), "got " + lead.persona()));
		}
		if (e.intent() != null) {
			checks.add(new Check("intent=" + e.intent(), e.intent().equals(lead.intent()), "got " + lead.intent()));
		}
		if (e.leadStatus() != null) {
			checks.add(new Check("leadStatus=" + e.leadStatus(), e.leadStatus().equals(lead.status()),
					"got " + lead.status()));
		}
		if (e.lead() != null) {
			e.lead().forEach((field, expected) -> {
				String actual = leadField(lead, field);
				checks.add(new Check("lead." + field + "~" + expected,
						actual != null && actual.toLowerCase().contains(expected.toLowerCase()), "got " + actual));
			});
		}
		if (e.mustMentionAny() != null) {
			String reply = finalReply.toLowerCase();
			int needed = e.minMentions() == null ? 1 : e.minMentions();
			var found = e.mustMentionAny().stream().filter(m -> reply.contains(m.toLowerCase())).toList();
			checks.add(new Check(needed == 1 ? "mentions any " + e.mustMentionAny()
					: "mentions at least " + needed + " of " + e.mustMentionAny(), found.size() >= needed,
					"found " + found));
		}
		// Safety patterns apply to every reply in the conversation; finalMustNotMatch only to the last one.
		if (e.mustNotMatch() != null) {
			for (String regex : e.mustNotMatch()) {
				var pattern = Pattern.compile(regex);
				var hit = replies.stream().map(pattern::matcher).filter(Matcher::find).map(Matcher::group).findFirst();
				checks.add(new Check("never says /" + regex + "/", hit.isEmpty(), "matched \"" + hit.orElse("") + "\""));
			}
		}
		if (e.finalMustNotMatch() != null) {
			for (String regex : e.finalMustNotMatch()) {
				var matcher = Pattern.compile(regex).matcher(finalReply);
				boolean found = matcher.find();
				checks.add(new Check("final reply avoids /" + regex + "/", !found,
						found ? "matched \"" + matcher.group() + "\"" : ""));
			}
		}
		if (e.maxReplyChars() != null) {
			int longest = replies.stream().mapToInt(String::length).max().orElse(0);
			checks.add(new Check("replies <= " + e.maxReplyChars() + " chars", longest <= e.maxReplyChars(),
					"longest " + longest));
		}
		checks.addAll(styleChecks(replies));
		return checks;
	}

	public static List<Check> styleChecks(List<String> replies) {
		int longest = 0;
		int mostQuestions = 0;
		String longestReply = "";
		String questionReply = "";
		for (String reply : replies) {
			int words = reply.isBlank() ? 0 : reply.strip().split("\\s+").length;
			int questions = (int) reply.chars().filter(ch -> ch == '?').count();
			if (words > longest) {
				longest = words;
				longestReply = reply;
			}
			if (questions > mostQuestions) {
				mostQuestions = questions;
				questionReply = reply;
			}
		}
		return List.of(
				new Check(CaseResult.STYLE_PREFIX + "<= " + MAX_WORDS + " words", longest <= MAX_WORDS,
						longest + " words: \"" + longestReply + "\""),
				new Check(CaseResult.STYLE_PREFIX + "<= " + MAX_QUESTIONS + " question", mostQuestions <= MAX_QUESTIONS,
						mostQuestions + " questions: \"" + questionReply + "\""));
	}

	/**
	 * LLM-as-judge on the final reply, given the exact chunks the agent saw. The judge must back every claim it
	 * accepts with a verbatim quote, and code verifies the quote exists.
	 */
	private Check judge(ChatService.Turn reply) {
		var knowledge = new StringBuilder();
		int n = 0;
		for (var source : reply.sources()) {
			var text = jdbc.sql("SELECT text FROM knowledge_chunk WHERE id::text = :id")
				.param("id", source.chunkId())
				.query(String.class)
				.optional();
			if (text.isPresent()) {
				knowledge.append("[").append(++n).append("]\n").append(text.get()).append("\n\n");
			}
		}
		var converter = new BeanOutputConverter<>(JudgeOutput.class);
		JudgeOutput parsed;
		try {
			String output = judge.prompt()
				.system(judgeInstructions)
				.options(ChatOptions.builder().model(judgeModel).temperature(0.0))
				.user("KNOWLEDGE:\n" + (knowledge.isEmpty() ? "(none)" : knowledge) + "\nREPLY:\n" + reply.reply()
						+ "\n\n" + converter.getFormat())
				.call()
				.content();
			parsed = converter.convert(output);
		}
		catch (RuntimeException ex) {
			return new Check(CaseResult.JUDGE_CHECK, false, "judge failed: " + ex.getMessage());
		}
		if (parsed == null || parsed.claims() == null) {
			return new Check(CaseResult.JUDGE_CHECK, false, "judge returned no claims");
		}
		String haystack = normalize(knowledge.toString());
		List<String> problems = new ArrayList<>();
		for (Claim claim : parsed.claims()) {
			// Only positive factual claims are scored; "we don't have that info" is allowed.
			if (!"FACT".equalsIgnoreCase(claim.type())) {
				continue;
			}
			if (!claim.supported()) {
				problems.add("unsupported: " + claim.claim());
				continue;
			}
			var spans = claim.evidence() == null ? List.<String>of() : claim.evidence();
			var missing = spans.stream().filter(e -> normalize(e).isBlank() || !haystack.contains(normalize(e))).toList();
			if (spans.isEmpty() || !missing.isEmpty()) {
				problems.add("evidence not found in knowledge: " + claim.claim() + " <- "
						+ (spans.isEmpty() ? "(none)" : missing));
			}
		}
		return new Check(CaseResult.JUDGE_CHECK, problems.isEmpty(), "problems=" + problems);
	}

	/** Case, punctuation, dashes and whitespace differences must not decide whether a quote "exists". */
	public static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
	}

	static String leadField(Lead lead, String field) {
		return switch (field) {
			case "name" -> lead.name();
			case "phone" -> lead.phone();
			case "email" -> lead.email();
			case "city" -> lead.city();
			case "projectType" -> lead.projectType();
			case "spaces" -> lead.spaces();
			case "finishInterest" -> lead.finishInterest();
			case "areaSize" -> lead.areaSize();
			case "timeline" -> lead.timeline();
			case "budget" -> lead.budget();
			case "callbackTime" -> lead.callbackTime();
			default -> throw new IllegalArgumentException("Unknown lead field in eval case: " + field);
		};
	}

	private static long elapsed(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

	record Claim(String claim, String type, boolean supported, List<String> evidence) {

	}

	record JudgeOutput(List<Claim> claims) {

	}

}
