package com.allhome.colourcoats.prompt;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stores a version's sections as one JSON array (column {@code prompt_version.sections}). */
@Converter
class PromptSectionsConverter implements AttributeConverter<List<PromptSection>, String> {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Override
	public String convertToDatabaseColumn(List<PromptSection> sections) {
		return sections == null ? null : JSON.writeValueAsString(sections);
	}

	@Override
	public List<PromptSection> convertToEntityAttribute(String json) {
		return json == null ? null : List.copyOf(JSON.readValue(json, new TypeReference<List<PromptSection>>() {
		}));
	}

}
