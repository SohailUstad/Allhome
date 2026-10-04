package com.allhome.colourcoats.prompt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/** One version of a system prompt: its sections, the text assembled from them, and where it is in its life cycle. */
@Entity
@Table(name = "prompt_version")
public class PromptVersion extends AssignedIdEntity {

	@Column(nullable = false, length = 100, updatable = false)
	private String name;

	@Column(nullable = false, updatable = false)
	private int versionNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PromptStatus status;

	@Convert(converter = PromptSectionsConverter.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<PromptSection> sections;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "content_sha256", nullable = false, length = 64)
	private String contentSha256;

	@Column(updatable = false)
	private UUID baseVersionId;

	@Column(columnDefinition = "text")
	private String note;

	@Column(nullable = false, length = 100, updatable = false)
	private String createdBy;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	private Instant activatedAt;

	@Column(length = 100)
	private String activatedBy;

	protected PromptVersion() {
	}

	PromptVersion(String name, int versionNumber, List<PromptSection> sections, UUID baseVersionId, String note,
			String createdBy, Instant now) {
		super(UUID.randomUUID());
		this.name = name;
		this.versionNumber = versionNumber;
		this.status = PromptStatus.DRAFT;
		this.baseVersionId = baseVersionId;
		this.note = note;
		this.createdBy = createdBy;
		this.createdAt = now;
		replaceSections(sections, note, now);
	}

	void replaceSections(List<PromptSection> newSections, String newNote, Instant now) {
		this.sections = List.copyOf(newSections);
		this.content = PromptDocument.assemble(this.sections);
		this.contentSha256 = Hashes.sha256(this.content);
		if (newNote != null && !newNote.isBlank()) {
			this.note = newNote.strip();
		}
		this.updatedAt = now;
	}

	/** Callers must first archive the prompt's active version; the database allows one active version per prompt. */
	void activate(String by, Instant now) {
		this.status = PromptStatus.ACTIVE;
		this.activatedAt = now;
		this.activatedBy = by;
		this.updatedAt = now;
	}

	void discard(Instant now) {
		this.status = PromptStatus.DISCARDED;
		this.updatedAt = now;
	}

	public Optional<PromptSection> section(String key) {
		return sections.stream().filter(section -> section.key().equals(key)).findFirst();
	}

	public boolean isDraft() {
		return status == PromptStatus.DRAFT;
	}

	public String getName() {
		return name;
	}

	public int getVersionNumber() {
		return versionNumber;
	}

	public PromptStatus getStatus() {
		return status;
	}

	public List<PromptSection> getSections() {
		return sections;
	}

	public String getContent() {
		return content;
	}

	public String getContentSha256() {
		return contentSha256;
	}

	public UUID getBaseVersionId() {
		return baseVersionId;
	}

	public String getNote() {
		return note;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getActivatedAt() {
		return activatedAt;
	}

	public String getActivatedBy() {
		return activatedBy;
	}

}
