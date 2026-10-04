package com.allhome.colourcoats.prompt;

import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.ChatSource;
import com.allhome.colourcoats.chat.Lead;
import com.allhome.colourcoats.chat.ModelAnswer;
import com.allhome.colourcoats.chat.SystemPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The console's test chat: talks to the real agent (real knowledge, real model) with any prompt version, usually a
 * draft before it is activated. The browser keeps the conversation; nothing is stored and no lead is created.
 */
@Controller
@RequestMapping("/prompts")
class PromptTestChatController {

	private static final Logger log = LoggerFactory.getLogger(PromptTestChatController.class);

	static final int MAX_MESSAGE = 2000;

	private final PromptService prompts;

	private final ChatService chat;

	private final int historySize;

	PromptTestChatController(PromptService prompts, ChatService chat,
			@Value("${chat.history-size:20}") int historySize) {
		this.prompts = prompts;
		this.chat = chat;
		this.historySize = historySize;
	}

	@PostMapping(path = "/{versionId}/test-chat", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	TestReply send(@PathVariable UUID versionId, @RequestBody TestMessage request) {
		PromptVersion version = prompts.version(versionId);
		String message = request.message() == null ? "" : request.message().strip();
		if (message.isEmpty() || message.length() > MAX_MESSAGE) {
			throw new PromptExceptions.RuleViolation("Type a message of at most " + MAX_MESSAGE + " characters");
		}
		Channel channel;
		try {
			channel = request.channel() == null ? Channel.ZOHO_SALESIQ : Channel.valueOf(request.channel());
		}
		catch (IllegalArgumentException ex) {
			throw new PromptExceptions.RuleViolation("Unknown channel '" + request.channel() + "'");
		}
		List<ChatRepository.StoredMessage> history = request.history() == null ? List.of()
				: request.history()
					.stream()
					.filter(m -> m != null && m.content() != null
							&& ("USER".equals(m.role()) || "ASSISTANT".equals(m.role())))
					.map(m -> new ChatRepository.StoredMessage(m.role(), m.content(), null))
					.toList();
		history = history.subList(Math.max(0, history.size() - historySize), history.size());

		ChatService.Turn turn = chat.preview(
				new SystemPrompts.SystemPrompt(version.getId(), version.getVersionNumber(), version.getContent()), null,
				history, known(request.lead()), message, channel);
		Lead lead = turn.lead();
		return new TestReply(turn.reply(), turn.handoff(), turn.handoffReason(), lead, lead.status(),
				turn.sources(), version.getVersionNumber());
	}

	/** The lead so far as the browser sent it back, cleaned the same way the agent's own extraction is. */
	private static Lead known(Lead lead) {
		if (lead == null) {
			return Lead.EMPTY;
		}
		return Lead.EMPTY.merge(lead.persona(), lead.intent(),
				new ModelAnswer.LeadDetails(lead.name(), lead.phone(), lead.email(), lead.city(), lead.projectType(),
						lead.spaces(), lead.finishInterest(), lead.areaSize(), lead.timeline(), lead.budget(),
						lead.callbackTime()));
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail invalid(PromptExceptions.RuleViolation ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail notFound(PromptExceptions.NotFound ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler
	@ResponseBody
	ProblemDetail failed(RuntimeException ex) {
		log.warn("Prompt test chat failed: {}", ex.toString());
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The agent could not answer (AI provider or database). Try again.");
	}

	record HistoryMessage(String role, String content) {

	}

	/**
	 * @param channel WEB_CHAT, ZOHO_SALESIQ (default) or INSTAGRAM
	 * @param history the conversation so far, oldest first
	 * @param lead what the agent learned so far (returned by the previous reply)
	 */
	record TestMessage(String message, String channel, List<HistoryMessage> history, Lead lead) {

	}

	record TestReply(String reply, boolean handoff, String handoffReason, Lead lead, String leadStatus,
			List<ChatSource> sources, int promptVersion) {

	}

}
