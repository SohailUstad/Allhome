package com.allhome.colourcoats.prompt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON shapes of the prompt API. */
final class PromptViews {

	private PromptViews() {
	}

	record Summary(UUID id, int versionNumber, PromptStatus status, String note, String createdBy, Instant createdAt,
			Instant updatedAt, Instant activatedAt, String activatedBy, UUID baseVersionId, String contentSha256) {

		static Summary of(PromptVersion version) {
			return new Summary(version.getId(), version.getVersionNumber(), version.getStatus(), version.getNote(),
					version.getCreatedBy(), version.getCreatedAt(), version.getUpdatedAt(), version.getActivatedAt(),
					version.getActivatedBy(), version.getBaseVersionId(), version.getContentSha256());
		}

	}

	record Section(String key, String title, SectionLock lock, String body) {

	}

	record Detail(Summary version, List<Section> sections, String content) {

		static Detail of(PromptVersion version, PromptService service) {
			var sections = version.getSections()
				.stream()
				.map(section -> new Section(section.key(), PromptService.label(section), service.lock(section.key()),
						section.body()))
				.toList();
			return new Detail(Summary.of(version), sections, version.getContent());
		}

	}

	/**
	 * @param sections section key to its new text; sections not listed stay as they are
	 * @param confirmProtected true to allow changes to protected sections
	 */
	record DraftRequest(Map<String, String> sections, String note, Boolean confirmProtected) {

		Map<String, String> edits() {
			return sections == null ? Map.of() : sections;
		}

		boolean confirmed() {
			return Boolean.TRUE.equals(confirmProtected);
		}

	}

}
