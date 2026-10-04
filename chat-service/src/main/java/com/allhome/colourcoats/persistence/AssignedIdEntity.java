package com.allhome.colourcoats.persistence;

import java.util.UUID;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities whose id is assigned by the application. Tells Spring Data that a freshly created instance is
 * new, so saving it inserts directly instead of first selecting by id.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

	@Id
	private UUID id;

	@Transient
	private boolean isNew = true;

	protected AssignedIdEntity() {
	}

	protected AssignedIdEntity(UUID id) {
		this.id = id;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		isNew = false;
	}

}
