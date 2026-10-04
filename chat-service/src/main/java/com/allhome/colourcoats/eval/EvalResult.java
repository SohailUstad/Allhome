package com.allhome.colourcoats.eval;

import java.time.Instant;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/** The result of one case in one run. Never updated. */
@Entity
@Table(name = "eval_result")
public class EvalResult extends AssignedIdEntity {

	@Column(nullable = false, updatable = false)
	private UUID runId;

	@Column(nullable = false, length = 100, updatable = false)
	private String caseId;

	@Column(nullable = false, updatable = false)
	private boolean passed;

	@Convert(converter = EvalEntities.ResultJson.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb", updatable = false)
	private CaseResult detail;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected EvalResult() {
	}

	EvalResult(UUID runId, CaseResult detail, Instant now) {
		super(UUID.randomUUID());
		this.runId = runId;
		this.caseId = detail.evalCase().id();
		this.passed = detail.behaviourPassed();
		this.detail = detail;
		this.createdAt = now;
	}

	public UUID getRunId() {
		return runId;
	}

	public String getCaseId() {
		return caseId;
	}

	public boolean isPassed() {
		return passed;
	}

	public CaseResult getDetail() {
		return detail;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
