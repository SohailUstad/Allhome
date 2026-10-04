package com.allhome.colourcoats.livemodel;

import java.time.Instant;
import java.util.UUID;

import com.allhome.colourcoats.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** One change of the live model, with who made it and why. Never updated. */
@Entity
@Table(name = "live_model_change")
public class LiveModelChange extends AssignedIdEntity {

	@Column(nullable = false, length = 100, updatable = false)
	private String model;

	@Column(nullable = false, length = 100, updatable = false)
	private String previousModel;

	@Column(nullable = false, columnDefinition = "text", updatable = false)
	private String reason;

	@Column(nullable = false, length = 100, updatable = false)
	private String changedBy;

	@Column(nullable = false, updatable = false)
	private Instant changedAt;

	protected LiveModelChange() {
	}

	LiveModelChange(String model, String previousModel, String reason, String changedBy, Instant changedAt) {
		super(UUID.randomUUID());
		this.model = model;
		this.previousModel = previousModel;
		this.reason = reason;
		this.changedBy = changedBy;
		this.changedAt = changedAt;
	}

	public String getModel() {
		return model;
	}

	public String getPreviousModel() {
		return previousModel;
	}

	public String getReason() {
		return reason;
	}

	public String getChangedBy() {
		return changedBy;
	}

	public Instant getChangedAt() {
		return changedAt;
	}

}
