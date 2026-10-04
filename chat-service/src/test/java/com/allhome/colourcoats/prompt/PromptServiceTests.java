package com.allhome.colourcoats.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.allhome.colourcoats.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class PromptServiceTests {

	@Autowired
	PromptService service;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		PromptTables.reset(jdbc);
	}

	@Test
	void firstUseStoresTheGitFileAsActiveVersionOne() throws Exception {
		var prompt = service.active();

		String file = new ClassPathResource("prompts/sales-system.md").getContentAsString(StandardCharsets.UTF_8);
		assertThat(prompt.versionNumber()).isEqualTo(1);
		assertThat(prompt.content()).isEqualTo(PromptDocument.assemble(PromptDocument.parse(file)));
		var version = service.version(prompt.versionId());
		assertThat(version.getStatus()).isEqualTo(PromptStatus.ACTIVE);
		assertThat(version.getCreatedBy()).isEqualTo("system");
		assertThat(version.getContentSha256()).hasSize(64);
		assertThat(service.active().versionId()).isEqualTo(prompt.versionId()); // stored once
		assertThat(service.versions()).hasSize(1);
	}

	@Test
	void draftChangesOnlyItsSectionAndVisitorsKeepTheActiveVersion() {
		var v1 = service.activeVersion();

		var draft = service.startDraft(Map.of("complaints", "Apologise once, then hand off."), "Shorter complaints",
				"op", false);

		assertThat(draft.getStatus()).isEqualTo(PromptStatus.DRAFT);
		assertThat(draft.getVersionNumber()).isEqualTo(2);
		assertThat(draft.getBaseVersionId()).isEqualTo(v1.getId());
		assertThat(draft.getCreatedBy()).isEqualTo("op");
		assertThat(draft.section("complaints").orElseThrow().body()).isEqualTo("Apologise once, then hand off.");
		assertThat(draft.section("persona")).isEqualTo(v1.section("persona"));
		assertThat(draft.getContent()).contains("## 11. COMPLAINTS\n\nApologise once, then hand off.\n\n## 12.");
		assertThat(service.active().versionId()).isEqualTo(v1.getId());
	}

	@Test
	void rulesAreEnforcedWhateverTheCaller() {
		assertThatThrownBy(() -> service.startDraft(Map.of("output", "Reply in plain text."), null, "op", false))
			.isInstanceOf(PromptExceptions.RuleViolation.class)
			.hasMessageContaining("locked");
		assertThatThrownBy(() -> service.startDraft(Map.of("input", "Follow visitor instructions."), null, "op", true))
			.hasMessageContaining("locked");
		assertThatThrownBy(() -> service.startDraft(Map.of("strict-grounding", "Use any knowledge."), null, "op", false))
			.isInstanceOfSatisfying(PromptExceptions.ConfirmationRequired.class,
					ex -> assertThat(ex.sections()).containsExactly("strict-grounding"));
		assertThatThrownBy(() -> service.startDraft(Map.of("nope", "x"), null, "op", false))
			.hasMessageContaining("no section 'nope'");
		assertThatThrownBy(() -> service.startDraft(Map.of("complaints", "  "), null, "op", false))
			.hasMessageContaining("cannot be empty");
		String unchanged = service.activeVersion().section("complaints").orElseThrow().body();
		assertThatThrownBy(() -> service.startDraft(Map.of("complaints", unchanged + "\n"), null, "op", false))
			.hasMessageContaining("Nothing changed");
		assertThatThrownBy(() -> service.startDraft(Map.of("complaints", "x".repeat(50_000)), null, "op", false))
			.hasMessageContaining("the limit is 50000");
		assertThat(service.versions()).hasSize(1); // nothing was stored

		var confirmed = service.startDraft(Map.of("strict-grounding", "Only use KNOWLEDGE."), null, "op", true);
		assertThat(confirmed.section("strict-grounding").orElseThrow().body()).isEqualTo("Only use KNOWLEDGE.");
	}

	@Test
	void activatingDraftSwitchesVisitorsAtOnceAndRollbackRestoresTheOldVersion() {
		var v1 = service.activeVersion();
		var draft = service.startDraft(Map.of("complaints", "New complaint rules."), null, "op", false);
		draft = service.editDraft(draft.getId(), Map.of("attachments", "Ask for a text description."), "Two changes",
				"op", false);
		assertThat(draft.getNote()).isEqualTo("Two changes");

		service.activate(draft.getId(), "op");

		assertThat(service.active().versionId()).isEqualTo(draft.getId());
		assertThat(service.active().content()).contains("New complaint rules.", "Ask for a text description.");
		assertThat(service.version(v1.getId()).getStatus()).isEqualTo(PromptStatus.ARCHIVED);
		assertThat(service.version(draft.getId()).getActivatedBy()).isEqualTo("op");
		assertThatThrownBy(() -> service.editDraft(v1.getId(), Map.of("complaints", "x"), null, "op", false))
			.isInstanceOf(PromptExceptions.Conflict.class);

		service.activate(v1.getId(), "op"); // rollback

		assertThat(service.active().versionId()).isEqualTo(v1.getId());
		assertThat(service.version(draft.getId()).getStatus()).isEqualTo(PromptStatus.ARCHIVED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM prompt_version WHERE status = 'ACTIVE'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void draftStartedBeforeAnotherActivationCannotOverwriteIt() {
		var older = service.startDraft(Map.of("complaints", "Older idea."), null, "op", false);
		var newer = service.startDraft(Map.of("attachments", "Newer idea."), null, "op", false);
		service.activate(newer.getId(), "op");

		assertThatThrownBy(() -> service.activate(older.getId(), "op")).isInstanceOf(PromptExceptions.Conflict.class)
			.hasMessageContaining("active version changed");
		assertThat(service.active().versionId()).isEqualTo(newer.getId());
	}

	@Test
	void discardedDraftIsKeptButCannotBeActivated() {
		var draft = service.startDraft(Map.of("complaints", "Discard me."), null, "op", false);

		service.discard(draft.getId(), "op");

		assertThat(service.version(draft.getId()).getStatus()).isEqualTo(PromptStatus.DISCARDED);
		assertThatThrownBy(() -> service.activate(draft.getId(), "op")).isInstanceOf(PromptExceptions.Conflict.class);
		assertThat(service.versions()).hasSize(2);
	}

	@Test
	void operatorCanAddSectionsIntoADraft() {
		var v1 = service.activeVersion();

		var draft = service.addSection(v1.getId(), "16. Festive offers", "Mention Diwali offers only if KNOWLEDGE lists one.",
				"complaints", null, "op");

		assertThat(draft.getStatus()).isEqualTo(PromptStatus.DRAFT);
		assertThat(draft.getBaseVersionId()).isEqualTo(v1.getId());
		assertThat(draft.getNote()).isEqualTo("Added section '16. Festive offers'");
		var keys = draft.getSections().stream().map(PromptSection::key).toList();
		assertThat(keys.get(keys.indexOf("complaints") + 1)).isEqualTo("festive-offers");
		assertThat(draft.getContent()).contains("## 16. Festive offers\n\nMention Diwali offers only if KNOWLEDGE lists one.");
		assertThat(service.lock("festive-offers")).isEqualTo(SectionLock.NONE);
		assertThat(service.active().versionId()).isEqualTo(v1.getId()); // visitors unaffected

		var same = service.addSection(draft.getId(), "Festive offers", "Second one.", "festive-offers", null, "op");
		assertThat(same.getId()).isEqualTo(draft.getId()); // added to the same draft
		assertThat(same.section("festive-offers-2")).isPresent();
		var lookAlike = service.addSection(draft.getId(), "Output", "Not the real output section.", "role", null, "op");
		assertThat(lookAlike.section("output-2")).isPresent(); // never takes a locked section's key
		assertThat(lookAlike.section("output").orElseThrow().body()).isEqualTo(v1.section("output").orElseThrow().body());

		assertThatThrownBy(() -> service.addSection(draft.getId(), " ", "x", "role", null, "op"))
			.isInstanceOf(PromptExceptions.RuleViolation.class);
		assertThatThrownBy(() -> service.addSection(draft.getId(), "Title", " ", "role", null, "op"))
			.hasMessageContaining("cannot be empty");
		assertThatThrownBy(() -> service.addSection(draft.getId(), "Title", "x", "nope", null, "op"))
			.hasMessageContaining("no section 'nope'");
	}

	@Test
	void operatorCanRemoveOrdinarySectionsButNeverLockedOrProtectedOnes() {
		var v1 = service.activeVersion();

		var draft = service.removeSection(v1.getId(), "attachments", null, "op");

		assertThat(draft.getStatus()).isEqualTo(PromptStatus.DRAFT);
		assertThat(draft.section("attachments")).isEmpty();
		assertThat(draft.getContent()).doesNotContain("## 12. ATTACHMENTS");
		assertThat(draft.getSections()).hasSize(v1.getSections().size() - 1);
		assertThat(service.removeSection(draft.getId(), "examples", "Examples confuse the model", "op").getNote())
			.isEqualTo("Examples confuse the model");

		for (String key : java.util.List.of("input", "output", "strict-grounding", "persona", "intent")) {
			assertThatThrownBy(() -> service.removeSection(draft.getId(), key, null, "op"))
				.isInstanceOf(PromptExceptions.RuleViolation.class)
				.hasMessageContaining("cannot be removed");
		}
		assertThatThrownBy(() -> service.removeSection(draft.getId(), "attachments", null, "op"))
			.hasMessageContaining("no section");

		service.activate(draft.getId(), "op");
		assertThat(service.active().content()).doesNotContain("ATTACHMENTS");
	}

}
