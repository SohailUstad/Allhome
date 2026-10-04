package com.allhome.colourcoats.eval;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

/** An eval case as stored and edited in the console. */
@Entity
@Table(name = "eval_case")
public class StoredCase {

	@Id
	@Column(length = 100)
	private String id;

	@Convert(converter = EvalEntities.CaseJson.class)
	@ColumnTransformer(write = "?::jsonb")
	@Column(nullable = false, columnDefinition = "jsonb")
	private EvalCase definition;

	@Column(nullable = false)
	private boolean enabled;

	@Column(nullable = false, length = 100)
	private String updatedBy;

	@Column(nullable = false)
	private Instant updatedAt;

	protected StoredCase() {
	}

	StoredCase(EvalCase definition, boolean enabled, String updatedBy, Instant now) {
		this.id = definition.id();
		update(definition, enabled, updatedBy, now);
	}

	void update(EvalCase newDefinition, boolean newEnabled, String by, Instant now) {
		this.definition = newDefinition;
		this.enabled = newEnabled;
		this.updatedBy = by;
		this.updatedAt = now;
	}

	public String getId() {
		return id;
	}

	public EvalCase getDefinition() {
		return definition;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
