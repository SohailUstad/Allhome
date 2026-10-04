package com.allhome.colourcoats.livemodel;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Models the operator may make live for visitors. Only fast, non-reasoning models: SalesIQ allows 3.8 s per reply.
 *
 * @param models the allowed models
 * @param minReasonLength shortest reason accepted for a change
 * @param refreshInterval how often other instances check for a change
 */
@ConfigurationProperties("live-model")
public record LiveModelProperties(@DefaultValue List<Model> models, @DefaultValue("15") int minReasonLength,
		@DefaultValue("30s") Duration refreshInterval) {

	public record Model(String id, String label, String description) {

		public Model {
			label = label == null || label.isBlank() ? id : label;
		}

	}

}
