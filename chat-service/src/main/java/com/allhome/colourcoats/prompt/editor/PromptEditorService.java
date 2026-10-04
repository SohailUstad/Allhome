package com.allhome.colourcoats.prompt.editor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.allhome.colourcoats.flowlog.FlowLog;
import com.allhome.colourcoats.prompt.LineDiff;
import com.allhome.colourcoats.prompt.PromptDocument;
import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptSection;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptStatus;
import com.allhome.colourcoats.prompt.PromptVersion;
import com.allhome.colourcoats.prompt.SectionLock;
import com.openai.models.completions.CompletionUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI prompt editor: the operator describes a behaviour change, a model chosen by the operator proposes the exact
 * section changes, and the operator accepts them into a draft, refines them, or discards them.
 *
 * <p>The model's proposal is never trusted: changes to unknown or locked sections, empty sections and changes that do
 * nothing are dropped (and reported), and accepting goes through the same checks as a manual edit. Nothing reaches
 * visitors until the operator activates the draft.
 */
@Service
public class PromptEditorService {

	private static final Logger log = LoggerFactory.getLogger(PromptEditorService.class);

	private static final String ANY_SECTION = "any";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final PromptService prompts;

	private final PromptEditRepository edits;

	private final EditorModels models;

	private final ChatModel chatModel;

	private final PromptEditorProperties properties;

	private final ResourceLoader resources;

	private final TransactionTemplate transaction;

	private final BeanOutputConverter<EditorAnswer> answerConverter = new BeanOutputConverter<>(EditorAnswer.class);

	private final Clock clock = Clock.systemUTC();

	PromptEditorService(PromptService prompts, PromptEditRepository edits, EditorModels models, ChatModel chatModel,
			PromptEditorProperties properties, ResourceLoader resources, PlatformTransactionManager transactionManager) {
		this.prompts = prompts;
		this.edits = edits;
		this.models = models;
		this.chatModel = chatModel;
		this.properties = properties;
		this.resources = resources;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Asks the editor model for a change to a draft or the active version.
	 * @param targetSection a section key, or null / "any" to let the editor choose
	 */
	public Proposal propose(UUID versionId, String instruction, String targetSection, String modelId, String effort,
			String by) {
		PromptVersion version = editable(prompts.version(versionId));
		String request = text(instruction, "Describe the change you want");
		String target = target(version, targetSection);
		EditorModels.Selection selection = models.select(modelId, effort);
		String input = input(version, target, request, null, null);
		var edit = new PromptEdit(version.getId(), version.getContentSha256(), null, request, target,
				selection.model().id(), selection.effort(), by, clock.instant());
		return ask(edit, version, selection, input);
	}

	/** Asks again with the operator's feedback on an earlier proposal (or answer to the editor's question). */
	public Proposal refine(UUID editId, String feedback, String modelId, String effort, String by) {
		PromptEdit parent = find(editId);
		if (!parent.isOpen()) {
			throw new PromptExceptions.Conflict("This proposal was already " + parent.getStatus().name().toLowerCase());
		}
		PromptVersion version = editable(prompts.version(parent.getVersionId()));
		String request = text(feedback, "Say what should be different");
		EditorModels.Selection selection = models.select(modelId, effort);
		String input = input(version, parent.getTargetSection(), parent.getInstruction(), parent.getProposal(),
				request);
		var edit = new PromptEdit(version.getId(), version.getContentSha256(), parent.getId(), request,
				parent.getTargetSection(), selection.model().id(), selection.effort(), by, clock.instant());
		Proposal proposal = ask(edit, version, selection, input);
		transaction.executeWithoutResult(status -> {
			PromptEdit reloaded = find(parent.getId());
			if (reloaded.isOpen()) {
				reloaded.decide(PromptEdit.Status.REFINED, null, clock.instant());
			}
		});
		return proposal;
	}

	/**
	 * Takes a proposal into a draft: into the same draft it was made for, or a new draft when it was made for the
	 * active version. Refused when the version changed after the proposal was made.
	 */
	public PromptVersion accept(UUID editId, boolean confirmProtected, String by) {
		PromptEdit edit = find(editId);
		if (edit.getStatus() != PromptEdit.Status.PROPOSED) {
			throw new PromptExceptions.Conflict("Only a proposal with changes can be accepted");
		}
		PromptVersion version = prompts.version(edit.getVersionId());
		if (!version.getContentSha256().equals(edit.getVersionSha256())
				|| (version.getStatus() != PromptStatus.DRAFT && version.getStatus() != PromptStatus.ACTIVE)) {
			throw new PromptExceptions.Conflict(
					"The prompt changed after this proposal was made; ask the AI again on the current version");
		}
		Map<String, String> changes = new LinkedHashMap<>();
		edit.getProposal().changes().forEach(change -> changes.put(change.key(), change.body()));
		String note = abbreviate("AI edit: " + rootInstruction(edit), 500);
		PromptVersion draft = version.isDraft() ? prompts.editDraft(version.getId(), changes, note, by, confirmProtected)
				: prompts.startDraft(changes, note, by, confirmProtected);
		transaction.executeWithoutResult(
				status -> find(editId).decide(PromptEdit.Status.ACCEPTED, draft.getId(), clock.instant()));
		log.info("{} accepted AI edit {} into draft version {}", by, editId, draft.getVersionNumber());
		return draft;
	}

	public void discard(UUID editId, String by) {
		transaction.executeWithoutResult(status -> {
			PromptEdit edit = find(editId);
			if (!edit.isOpen()) {
				throw new PromptExceptions.Conflict("This proposal was already " + edit.getStatus().name().toLowerCase());
			}
			edit.decide(PromptEdit.Status.DISCARDED, null, clock.instant());
		});
		log.info("{} discarded AI edit {}", by, editId);
	}

	/** The latest AI requests made for a version, newest first. */
	public List<PromptEdit> recent(UUID versionId) {
		return edits.findTop10ByVersionIdOrderByCreatedAtDesc(versionId);
	}

	public PromptEdit edit(UUID editId) {
		return find(editId);
	}

	/** An earlier request as the console shows it. */
	public Proposal view(UUID editId) {
		PromptEdit edit = find(editId);
		return Proposal.of(edit, prompts.version(edit.getVersionId()), prompts);
	}

	public List<EditorModels.Choice> modelChoices() {
		return models.choices();
	}

	// ---- The model call ----------------------------------------------------------------------------------------

	private Proposal ask(PromptEdit edit, PromptVersion version, EditorModels.Selection selection, String input) {
		FlowLog.log("prompt_editor.request", "model", selection.model().id(), "reasoning_effort", selection.effort(),
				"prompt_version", version.getVersionNumber(), "target_section", edit.getTargetSection(),
				"instruction", edit.getInstruction());
		long started = System.nanoTime();
		ChatResponse response;
		try {
			response = chatModel.call(new Prompt(List.of(new SystemMessage(instructions()), new UserMessage(input)),
					options(selection)));
		}
		catch (RuntimeException ex) {
			long duration = elapsedMs(started);
			log.warn("Prompt editor call to {} failed after {} ms: {}", selection.model().id(), duration, ex.toString());
			edit.answered(PromptEdit.Status.FAILED, null, "The model call failed: " + ex.getMessage(), null, null, null,
					duration);
			return save(edit, version);
		}
		long duration = elapsedMs(started);
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		String output = response.getResult() == null || response.getResult().getOutput() == null ? null
				: response.getResult().getOutput().getText();
		FlowLog.log("prompt_editor.response", "model", selection.model().id(), "duration_ms", duration,
				"prompt_tokens", usage == null ? null : usage.getPromptTokens(), "completion_tokens",
				usage == null ? null : usage.getCompletionTokens(), "content", output);

		EditorAnswer answer = parse(output);
		PromptEdit.Status status;
		String error = null;
		if (answer == null) {
			status = PromptEdit.Status.FAILED;
			error = "The model did not return a usable answer";
		}
		else {
			answer = checked(answer, version);
			status = switch (answer.outcome()) {
				case "QUESTION" -> PromptEdit.Status.QUESTION;
				case "REFUSE" -> PromptEdit.Status.REFUSED;
				default -> answer.changes().isEmpty() ? PromptEdit.Status.FAILED : PromptEdit.Status.PROPOSED;
			};
			if (status == PromptEdit.Status.FAILED) {
				error = "The model proposed no usable change";
			}
		}
		edit.answered(status, answer, error, usage == null ? null : usage.getPromptTokens(),
				usage == null ? null : usage.getCompletionTokens(), reasoningTokens(usage), duration);
		return save(edit, version);
	}

	OpenAiChatOptions options(EditorModels.Selection selection) {
		return requestOptions(selection, properties);
	}

	/** Standard models answer at temperature 0; reasoning models take a reasoning effort and no temperature. */
	public static OpenAiChatOptions requestOptions(EditorModels.Selection selection, PromptEditorProperties properties) {
		var options = OpenAiChatOptions.builder()
			.model(selection.model().id())
			.maxCompletionTokens(properties.maxOutputTokens())
			.timeout(properties.timeout())
			.responseFormat(OpenAiChatModel.ResponseFormat.builder()
				.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
				.build());
		if (selection.model().reasoning()) {
			options.reasoningEffort(selection.effort());
		}
		else {
			options.temperature(0.0);
		}
		return options.build();
	}

	/** The prompt in sections with their locks, the request, and (when refining) the previous proposal. */
	private String input(PromptVersion version, String target, String instruction, EditorAnswer previous,
			String feedback) {
		var text = new StringBuilder("<prompt>\n");
		for (PromptSection section : version.getSections()) {
			text.append("<section key=\"")
				.append(section.key())
				.append("\" title=\"")
				.append(PromptService.label(section))
				.append("\" lock=\"")
				.append(prompts.lock(section.key()))
				.append("\">\n")
				.append(section.body())
				.append("\n</section>\n");
		}
		text.append("</prompt>\n\nTARGET SECTION: ").append(target == null ? ANY_SECTION : target);
		text.append("\n\nREQUEST:\n").append(instruction);
		if (previous != null) {
			text.append("\n\nPREVIOUS PROPOSAL:\n").append(JSON.writeValueAsString(previous));
		}
		if (feedback != null) {
			text.append("\n\nFEEDBACK:\n").append(feedback);
		}
		return text.append("\n\n").append(answerConverter.getFormat()).toString();
	}

	private EditorAnswer parse(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			EditorAnswer answer = answerConverter.convert(output);
			if (answer == null || answer.outcome() == null) {
				return null;
			}
			String outcome = answer.outcome().strip().toUpperCase();
			if (!List.of("PROPOSE", "QUESTION", "REFUSE").contains(outcome)) {
				return null;
			}
			return new EditorAnswer(outcome, answer.summary(), answer.changes(), answer.conflicts(), answer.question(),
					answer.refusal());
		}
		catch (RuntimeException ex) {
			log.warn("Prompt editor returned unparseable output: {}", ex.toString());
			return null;
		}
	}

	/**
	 * Keeps only changes that can be applied: known section, not locked, not empty, actually different. Dropped
	 * changes are reported to the operator as conflicts.
	 */
	EditorAnswer checked(EditorAnswer answer, PromptVersion version) {
		if (!"PROPOSE".equals(answer.outcome())) {
			return new EditorAnswer(answer.outcome(), answer.summary(), List.of(), answer.conflicts(), answer.question(),
					answer.refusal());
		}
		List<EditorAnswer.Change> kept = new ArrayList<>();
		List<String> notes = new ArrayList<>(answer.conflicts());
		Map<String, String> seen = new LinkedHashMap<>();
		for (EditorAnswer.Change change : answer.changes()) {
			Optional<PromptSection> section = change.key() == null ? Optional.empty() : version.section(change.key());
			if (section.isEmpty()) {
				notes.add("Ignored a change to an unknown section '" + change.key() + "'.");
			}
			else if (prompts.lock(change.key()) == SectionLock.LOCKED) {
				notes.add("Ignored a change to the locked section '" + PromptService.label(section.get()) + "'.");
			}
			else if (change.body() == null || change.body().isBlank()) {
				notes.add("Ignored an empty text for '" + PromptService.label(section.get()) + "'.");
			}
			else if (same(change.body(), section.get().body()) || seen.containsKey(change.key())) {
				// unchanged, or the same section twice: keep the first
			}
			else {
				seen.put(change.key(), change.body());
				kept.add(new EditorAnswer.Change(change.key(), PromptDocument.normalise(change.body()).strip(),
						change.reason()));
			}
		}
		if (!kept.isEmpty()) {
			try {
				prompts.apply(version.getSections(), seen, true, false); // size limit and the other rules
			}
			catch (PromptExceptions.RuleViolation ex) {
				notes.add("The proposal cannot be used: " + ex.getMessage());
				kept.clear();
			}
		}
		return new EditorAnswer("PROPOSE", answer.summary(), kept, notes, null, null);
	}

	// ---- Helpers -----------------------------------------------------------------------------------------------

	private Proposal save(PromptEdit edit, PromptVersion version) {
		PromptEdit saved = transaction.execute(status -> edits.saveAndFlush(edit));
		log.info("AI edit {} by {} with {}{}: {} in {} ms", saved.getId(), saved.getCreatedBy(), saved.getModel(),
				saved.getReasoningEffort() == null ? "" : " (" + saved.getReasoningEffort() + ")", saved.getStatus(),
				saved.getDurationMs());
		return Proposal.of(saved, version, prompts);
	}

	private PromptVersion editable(PromptVersion version) {
		if (version.getStatus() != PromptStatus.DRAFT && version.getStatus() != PromptStatus.ACTIVE) {
			throw new PromptExceptions.Conflict("Only the active version or a draft can be edited");
		}
		return version;
	}

	private String target(PromptVersion version, String targetSection) {
		if (targetSection == null || targetSection.isBlank() || ANY_SECTION.equals(targetSection)) {
			return null;
		}
		PromptSection section = version.section(targetSection)
			.orElseThrow(() -> new PromptExceptions.RuleViolation("There is no section '" + targetSection + "'"));
		if (prompts.lock(section.key()) == SectionLock.LOCKED) {
			throw new PromptExceptions.RuleViolation("Section '" + PromptService.label(section) + "' is locked");
		}
		return section.key();
	}

	private String text(String value, String missing) {
		if (value == null || value.isBlank()) {
			throw new PromptExceptions.RuleViolation(missing);
		}
		if (value.length() > properties.maxInstructionLength()) {
			throw new PromptExceptions.RuleViolation(
					"Keep it under " + properties.maxInstructionLength() + " characters");
		}
		return value.strip();
	}

	private PromptEdit find(UUID editId) {
		return edits.findById(editId)
			.orElseThrow(() -> new PromptExceptions.RuleViolation("AI edit " + editId + " does not exist"));
	}

	/** The operator's first request in a chain of refinements, used as the draft's note. */
	private String rootInstruction(PromptEdit edit) {
		PromptEdit current = edit;
		while (current.getParentEditId() != null) {
			current = edits.findById(current.getParentEditId()).orElse(null);
			if (current == null) {
				return edit.getInstruction();
			}
		}
		return current.getInstruction();
	}

	private String instructions() {
		try {
			return resources.getResource(properties.instructions()).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read the prompt editor instructions " + properties.instructions(), ex);
		}
	}

	/** Reasoning models report their hidden reasoning tokens separately (part of the completion tokens). */
	private static Integer reasoningTokens(Usage usage) {
		if (usage != null && usage.getNativeUsage() instanceof CompletionUsage completion) {
			return completion.completionTokensDetails()
				.flatMap(details -> details.reasoningTokens())
				.map(Math::toIntExact)
				.orElse(null);
		}
		return null;
	}

	private static boolean same(String a, String b) {
		return PromptDocument.normalise(a).strip().equals(PromptDocument.normalise(b).strip());
	}

	private static String abbreviate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

	/**
	 * A proposal as the console shows it: each change with its diff against the version it was made for.
	 * {@code outdated} is true when that version changed since, so the proposal can no longer be accepted.
	 */
	public record Proposal(UUID id, String status, String summary, List<ChangeView> changes, List<String> conflicts,
			String question, String refusal, String error, String model, String effort, Integer promptTokens,
			Integer completionTokens, Integer reasoningTokens, Long durationMs, boolean needsConfirmation,
			boolean outdated) {

		static Proposal of(PromptEdit edit, PromptVersion version, PromptService prompts) {
			EditorAnswer answer = edit.getProposal();
			List<ChangeView> changes = new ArrayList<>();
			if (answer != null) {
				for (EditorAnswer.Change change : answer.changes()) {
					PromptSection section = version.section(change.key()).orElse(null);
					if (section != null) {
						changes.add(new ChangeView(change.key(), PromptService.label(section), change.reason(),
								prompts.lock(change.key()) == SectionLock.PROTECTED,
								LineDiff.between(section.body(), change.body())));
					}
				}
			}
			return new Proposal(edit.getId(), edit.getStatus().name(), answer == null ? null : answer.summary(),
					changes, answer == null ? List.of() : answer.conflicts(), answer == null ? null : answer.question(),
					answer == null ? null : answer.refusal(), edit.getError(), edit.getModel(),
					edit.getReasoningEffort(), edit.getPromptTokens(), edit.getCompletionTokens(),
					edit.getReasoningTokens(), edit.getDurationMs(), changes.stream().anyMatch(ChangeView::protectedSection),
					!version.getContentSha256().equals(edit.getVersionSha256()));
		}

	}

	public record ChangeView(String key, String title, String reason, boolean protectedSection,
			List<LineDiff.Line> diff) {

	}

}
