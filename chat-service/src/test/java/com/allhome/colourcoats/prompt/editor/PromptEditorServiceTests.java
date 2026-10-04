package com.allhome.colourcoats.prompt.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.allhome.colourcoats.IntegrationTest;
import com.allhome.colourcoats.prompt.PromptExceptions;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.prompt.PromptStatus;
import com.allhome.colourcoats.prompt.PromptTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The AI editor against the real prompt store, with the model replaced by scripted answers. */
@IntegrationTest
class PromptEditorServiceTests {

	@Autowired
	PromptService prompts;

	@Autowired
	PromptEditRepository edits;

	@Autowired
	EditorModels models;

	@Autowired
	PromptEditorProperties properties;

	@Autowired
	ResourceLoader resources;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	JdbcTemplate jdbc;

	final ChatModel model = mock(ChatModel.class);

	PromptEditorService editor;

	@BeforeEach
	void setUp() {
		PromptTables.reset(jdbc);
		editor = new PromptEditorService(prompts, edits, models, model, properties, resources, transactions);
	}

	void modelAnswers(String json) {
		when(model.call(any(Prompt.class)))
			.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
	}

	@Test
	void proposalKeepsOnlyUsableChangesAndShowsTheirDiff() {
		var active = prompts.activeVersion();
		modelAnswers("""
				{"outcome":"PROPOSE","summary":"Complaints get one apology, then a handoff.",
				 "changes":[{"key":"complaints","body":"Apologise once.\\nThen hand off.","reason":"Shorter"},
				            {"key":"output","body":"Reply in plain text.","reason":"no"},
				            {"key":"nope","body":"x","reason":"no"}],
				 "conflicts":["Section 8 also mentions complaints."],"question":null,"refusal":null}
				""");

		var proposal = editor.propose(active.getId(), "Be brief with complaints", "complaints", "gpt-4.1", null, "op");

		assertThat(proposal.status()).isEqualTo("PROPOSED");
		assertThat(proposal.summary()).isEqualTo("Complaints get one apology, then a handoff.");
		assertThat(proposal.changes()).singleElement().satisfies(change -> {
			assertThat(change.key()).isEqualTo("complaints");
			assertThat(change.title()).isEqualTo("11. COMPLAINTS");
			assertThat(change.diff()).anyMatch(line -> line.kind().name().equals("ADDED") && line.text().equals("Apologise once."));
		});
		assertThat(proposal.conflicts()).containsExactly("Section 8 also mentions complaints.",
				"Ignored a change to the locked section '15. OUTPUT'.",
				"Ignored a change to an unknown section 'nope'.");
		assertThat(proposal.needsConfirmation()).isFalse();
		assertThat(proposal.model()).isEqualTo("gpt-4.1");
		assertThat(prompts.active().versionId()).isEqualTo(active.getId()); // nothing changes for visitors

		PromptEdit stored = edits.findById(proposal.id()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(PromptEdit.Status.PROPOSED);
		assertThat(stored.getInstruction()).isEqualTo("Be brief with complaints");
		assertThat(stored.getTargetSection()).isEqualTo("complaints");
		assertThat(stored.getVersionSha256()).isEqualTo(active.getContentSha256());
		assertThat(stored.getProposal().changes()).hasSize(1);
		assertThat(stored.getDurationMs()).isNotNull();
	}

	@Test
	void standardModelsRunAtTemperatureZeroAndReasoningModelsWithTheirEffort() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"QUESTION\",\"summary\":\"Unclear\",\"changes\":[],\"conflicts\":[],"
				+ "\"question\":\"For which channel?\",\"refusal\":null}");
		var prompt = ArgumentCaptor.forClass(Prompt.class);

		var question = editor.propose(active.getId(), "Shorter replies", null, "gpt-4.1", null, "op");
		editor.propose(active.getId(), "Shorter replies", "any", "gpt-5", "high", "op");

		assertThat(question.status()).isEqualTo("QUESTION");
		assertThat(question.question()).isEqualTo("For which channel?");
		verify(model, org.mockito.Mockito.times(2)).call(prompt.capture());
		var standard = (OpenAiChatOptions) prompt.getAllValues().get(0).getOptions();
		assertThat(standard.getModel()).isEqualTo("gpt-4.1");
		assertThat(standard.getTemperature()).isEqualTo(0.0);
		assertThat(standard.getReasoningEffort()).isNull();
		assertThat(standard.getResponseFormat().getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
		assertThat(standard.getMaxCompletionTokens()).isEqualTo(properties.maxOutputTokens());
		assertThat(standard.getTimeout()).isEqualTo(properties.timeout());
		var reasoning = (OpenAiChatOptions) prompt.getAllValues().get(1).getOptions();
		assertThat(reasoning.getModel()).isEqualTo("gpt-5");
		assertThat(reasoning.getReasoningEffort()).isEqualTo("high");
		assertThat(reasoning.getTemperature()).isNull();

		var messages = prompt.getAllValues().get(0).getInstructions();
		assertThat(messages.get(0).getText()).startsWith("You edit the system prompt of Aira");
		assertThat(messages.get(1).getText()).contains("<section key=\"output\" title=\"15. OUTPUT\" lock=\"LOCKED\">",
				"lock=\"PROTECTED\"", "TARGET SECTION: any", "REQUEST:\nShorter replies");
	}

	@Test
	void refusalsFailuresAndBrokenOutputAreRecordedNotApplied() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"REFUSE\",\"summary\":\"A price is a fact.\",\"changes\":[],\"conflicts\":[],"
				+ "\"question\":null,\"refusal\":\"Add prices to the knowledge base.\"}");
		assertThat(editor.propose(active.getId(), "Lime wash costs 80/sq ft", null, null, null, "op").refusal())
			.isEqualTo("Add prices to the knowledge base.");

		modelAnswers("Sure! Here is the new prompt...");
		var broken = editor.propose(active.getId(), "Anything", null, null, null, "op");
		assertThat(broken.status()).isEqualTo("FAILED");
		assertThat(broken.error()).contains("usable answer");

		when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("429 rate limited"));
		var failed = editor.propose(active.getId(), "Anything", null, null, null, "op");
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.error()).contains("429 rate limited");

		assertThat(edits.count()).isEqualTo(3);
		assertThat(prompts.versions()).hasSize(1);
	}

	@Test
	void inputIsCheckedBeforeAnyModelCall() {
		var active = prompts.activeVersion();
		assertThatThrownBy(() -> editor.propose(active.getId(), "  ", null, null, null, "op"))
			.isInstanceOf(PromptExceptions.RuleViolation.class);
		assertThatThrownBy(() -> editor.propose(active.getId(), "x", "output", null, null, "op"))
			.hasMessageContaining("locked");
		assertThatThrownBy(() -> editor.propose(active.getId(), "x", null, "gpt-5", "extreme", "op"))
			.hasMessageContaining("reasoning effort");
		org.mockito.Mockito.verifyNoInteractions(model);
	}

	@Test
	void acceptingPutsTheChangeIntoADraftAndRefusesOutdatedProposals() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"s\",\"changes\":[{\"key\":\"complaints\","
				+ "\"body\":\"AI complaint rule.\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var first = editor.propose(active.getId(), "Change complaints", null, null, null, "op");

		var draft = editor.accept(first.id(), false, "op");

		assertThat(draft.getStatus()).isEqualTo(PromptStatus.DRAFT);
		assertThat(draft.getNote()).isEqualTo("AI edit: Change complaints");
		assertThat(draft.section("complaints").orElseThrow().body()).isEqualTo("AI complaint rule.");
		assertThat(edits.findById(first.id()).orElseThrow().getResultVersionId()).isEqualTo(draft.getId());
		assertThatThrownBy(() -> editor.accept(first.id(), false, "op")).isInstanceOf(PromptExceptions.Conflict.class);

		// A proposal for the draft goes into the same draft.
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"s\",\"changes\":[{\"key\":\"attachments\","
				+ "\"body\":\"AI attachment rule.\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var second = editor.propose(draft.getId(), "Change attachments", null, null, null, "op");
		var stale = editor.propose(draft.getId(), "Change attachments too", null, null, null, "op");
		var sameDraft = editor.accept(second.id(), false, "op");
		assertThat(sameDraft.getId()).isEqualTo(draft.getId());
		assertThat(sameDraft.getContent()).contains("AI complaint rule.", "AI attachment rule.");

		assertThat(editor.view(stale.id()).outdated()).isTrue();
		assertThatThrownBy(() -> editor.accept(stale.id(), false, "op")).isInstanceOf(PromptExceptions.Conflict.class)
			.hasMessageContaining("changed after this proposal");
	}

	@Test
	void protectedChangesNeedConfirmationWhenAccepted() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"s\",\"changes\":[{\"key\":\"persona\","
				+ "\"body\":\"HOMEOWNER — own home or flat\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var proposal = editor.propose(active.getId(), "Clarify homeowner", null, null, null, "op");
		assertThat(proposal.needsConfirmation()).isTrue();

		assertThatThrownBy(() -> editor.accept(proposal.id(), false, "op"))
			.isInstanceOf(PromptExceptions.ConfirmationRequired.class);
		assertThat(editor.accept(proposal.id(), true, "op").getStatus()).isEqualTo(PromptStatus.DRAFT);
	}

	@Test
	void refiningSendsThePreviousProposalAndFeedback() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"first\",\"changes\":[{\"key\":\"complaints\","
				+ "\"body\":\"Version A.\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var first = editor.propose(active.getId(), "Change complaints", "complaints", null, null, "op");
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"second\",\"changes\":[{\"key\":\"complaints\","
				+ "\"body\":\"Version B.\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var prompt = ArgumentCaptor.forClass(Prompt.class);

		var second = editor.refine(first.id(), "Shorter please", "o3", "low", "op");

		verify(model, org.mockito.Mockito.times(2)).call(prompt.capture());
		assertThat(prompt.getValue().getInstructions().get(1).getText()).contains("REQUEST:\nChange complaints",
				"PREVIOUS PROPOSAL:", "Version A.", "FEEDBACK:\nShorter please", "TARGET SECTION: complaints");
		assertThat(second.summary()).isEqualTo("second");
		assertThat(second.effort()).isEqualTo("low");
		assertThat(edits.findById(first.id()).orElseThrow().getStatus()).isEqualTo(PromptEdit.Status.REFINED);
		assertThat(edits.findById(second.id()).orElseThrow().getParentEditId()).isEqualTo(first.id());
		assertThat(editor.accept(second.id(), false, "op").getNote()).isEqualTo("AI edit: Change complaints");
	}

	@Test
	void discardedProposalCannotBeAcceptedOrRefined() {
		var active = prompts.activeVersion();
		modelAnswers("{\"outcome\":\"PROPOSE\",\"summary\":\"s\",\"changes\":[{\"key\":\"complaints\","
				+ "\"body\":\"x.\",\"reason\":\"r\"}],\"conflicts\":[]}");
		var proposal = editor.propose(active.getId(), "Change", null, null, null, "op");

		editor.discard(proposal.id(), "op");

		assertThatThrownBy(() -> editor.accept(proposal.id(), false, "op")).isInstanceOf(PromptExceptions.Conflict.class);
		assertThatThrownBy(() -> editor.refine(proposal.id(), "again", null, null, "op"))
			.isInstanceOf(PromptExceptions.Conflict.class);
		assertThat(editor.recent(active.getId())).extracting(PromptEdit::getStatus)
			.containsExactly(PromptEdit.Status.DISCARDED);
	}

}
