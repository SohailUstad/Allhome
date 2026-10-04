package com.allhome.colourcoats.eval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/** One eval run: a prompt version and a model against a set of cases. */
@Entity
@Table(name = "eval_run")
public class EvalRun extends AssignedIdEntity {

	@Column(nullable = false, updatable = false)
	private UUID promptVersionId;

	@Column(nullable = false, length = 100, updatable = false)
	private String model;

	@Column(nullable = false, updatable = false)
	private boolean judge;

	@Column(nullable = false, updatable = false)
	private boolean fullRun;

	@Convert(converter = EvalEntities.IdsJson.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<String> caseIds;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(nullable = false)
	private int passedCases;

	@Column(nullable = false, length = 100, updatable = false)
	private String createdBy;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant finishedAt;

	protected EvalRun() {
	}

	EvalRun(UUID promptVersionId, String model, boolean judge, boolean fullRun, List<String> caseIds, String createdBy,
			Instant now) {
		super(UUID.randomUUID());
		this.promptVersionId = promptVersionId;
		this.model = model;
		this.judge = judge;
		this.fullRun = fullRun;
		this.caseIds = List.copyOf(caseIds);
		this.status = "RUNNING";
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	void complete(int passed, Instant now) {
		this.status = "COMPLETED";
		this.passedCases = passed;
		this.finishedAt = now;
	}

	public boolean isCompleted() {
		return "COMPLETED".equals(status);
	}

	/** Share of cases whose behaviour checks passed, 0..1. */
	public double passRate() {
		return caseIds.isEmpty() ? 0 : (double) passedCases / caseIds.size();
	}

	public UUID getPromptVersionId() {
		return promptVersionId;
	}

	public String getModel() {
		return model;
	}

	public boolean isJudge() {
		return judge;
	}

	public boolean isFullRun() {
		return fullRun;
	}

	public List<String> getCaseIds() {
		return caseIds;
	}

	public String getStatus() {
		return status;
	}

	public int getPassedCases() {
		return passedCases;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

}
