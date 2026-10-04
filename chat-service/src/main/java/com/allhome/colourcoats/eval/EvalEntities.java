package com.allhome.colourcoats.eval;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON column converters shared by the eval entities. */
final class EvalEntities {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private EvalEntities() {
	}

	@jakarta.persistence.Converter
	static class CaseJson implements AttributeConverter<EvalCase, String> {

		@Override
		public String convertToDatabaseColumn(EvalCase value) {
			return JSON.writeValueAsString(value);
		}

		@Override
		public EvalCase convertToEntityAttribute(String json) {
			return JSON.readValue(json, EvalCase.class);
		}

	}

	@jakarta.persistence.Converter
	static class ResultJson implements AttributeConverter<CaseResult, String> {

		@Override
		public String convertToDatabaseColumn(CaseResult value) {
			return JSON.writeValueAsString(value);
		}

		@Override
		public CaseResult convertToEntityAttribute(String json) {
			return JSON.readValue(json, CaseResult.class);
		}

	}

	@jakarta.persistence.Converter
	static class IdsJson implements AttributeConverter<List<String>, String> {

		@Override
		public String convertToDatabaseColumn(List<String> value) {
			return JSON.writeValueAsString(value);
		}

		@Override
		public List<String> convertToEntityAttribute(String json) {
			return JSON.readValue(json, new TypeReference<List<String>>() {
			});
		}

	}

}
