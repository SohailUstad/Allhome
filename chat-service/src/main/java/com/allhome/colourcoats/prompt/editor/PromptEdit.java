package com.allhome.colourcoats.prompt.editor;

import java.time.Instant;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import tools.jackson.databind.json.JsonMapper;

/** One request to the AI prompt editor and what became of it. */
@Entity
@Table(name = "prompt_edit")
public class PromptEdit extends AssignedIdEntity {

	public enum Status {

		/** Changes are ready to review. */
		PROPOSED,
		/** The editor asked something back. */
		QUESTION,
		/** The editor declined (not a prompt change). */
		REFUSED,
		/** The model call failed or returned nothing usable. */
		FAILED,
		/** The operator took the changes into a draft. */
		ACCEPTED,
		/** The operator asked for a revised proposal (a newer request refers to this one). */
		REFINED,
		/** The operator threw the proposal away. */
		DISCARDED

	}

	@Column(nullable = false, updatable = false)
	private UUID versionId;

	@Column(name = "version_sha256", nullable = false, length = 64, updatable = false)
	private String versionSha256;

	@Column(updatable = false)
	private UUID parentEditId;

	@Column(nullable = false, columnDefinition = "text", updatable = false)
	private String instruction;

	@Column(length = 100, updatable = false)
	private String targetSection;

	@Column(nullable = false, length = 100, updatable = false)
	private String model;

	@Column(length = 20, updatable = false)
	private String reasoningEffort;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Convert(converter = AnswerConverter.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(columnDefinition = "jsonb")
	private EditorAnswer proposal;

	@Column(columnDefinition = "text")
	private String error;

	private Integer promptTokens;

	private Integer completionTokens;

	private Integer reasoningTokens;

	private Long durationMs;

	private UUID resultVersionId;

	@Column(nullable = false, length = 100, updatable = false)
	private String createdBy;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant decidedAt;

	protected PromptEdit() {
	}

	PromptEdit(UUID versionId, String versionSha256, UUID parentEditId, String instruction, String targetSection,
			String model, String reasoningEffort, String createdBy, Instant now) {
		super(UUID.randomUUID());
		this.versionId = versionId;
		this.versionSha256 = versionSha256;
		this.parentEditId = parentEditId;
		this.instruction = instruction;
		this.targetSection = targetSection;
		this.model = model;
		this.reasoningEffort = reasoningEffort;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.status = Status.FAILED; // until an answer is recorded
	}

	void answered(Status status, EditorAnswer proposal, String error, Integer promptTokens, Integer completionTokens,
			Integer reasoningTokens, long durationMs) {
		this.status = status;
		this.proposal = proposal;
		this.error = error;
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		this.reasoningTokens = reasoningTokens;
		this.durationMs = durationMs;
	}

	void decide(Status decision, UUID resultVersionId, Instant now) {
		this.status = decision;
		this.resultVersionId = resultVersionId;
		this.decidedAt = now;
	}

	public boolean isOpen() {
		return status == Status.PROPOSED || status == Status.QUESTION || status == Status.REFUSED;
	}

	public UUID getVersionId() {
		return versionId;
	}

	public String getVersionSha256() {
		return versionSha256;
	}

	public UUID getParentEditId() {
		return parentEditId;
	}

	public String getInstruction() {
		return instruction;
	}

	public String getTargetSection() {
		return targetSection;
	}

	public String getModel() {
		return model;
	}

	public String getReasoningEffort() {
		return reasoningEffort;
	}

	public Status getStatus() {
		return status;
	}

	public EditorAnswer getProposal() {
		return proposal;
	}

	public String getError() {
		return error;
	}

	public Integer getPromptTokens() {
		return promptTokens;
	}

	public Integer getCompletionTokens() {
		return completionTokens;
	}

	public Integer getReasoningTokens() {
		return reasoningTokens;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public UUID getResultVersionId() {
		return resultVersionId;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	@Converter
	static class AnswerConverter implements AttributeConverter<EditorAnswer, String> {

		private static final JsonMapper JSON = JsonMapper.builder().build();

		@Override
		public String convertToDatabaseColumn(EditorAnswer answer) {
			return answer == null ? null : JSON.writeValueAsString(answer);
		}

		@Override
		public EditorAnswer convertToEntityAttribute(String json) {
			return json == null ? null : JSON.readValue(json, EditorAnswer.class);
		}

	}

}
