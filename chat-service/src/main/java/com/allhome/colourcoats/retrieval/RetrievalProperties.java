package com.allhome.colourcoats.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param topK maximum chunks returned per question
 * @param minSimilarity cosine similarity (0..1) a chunk needs to be returned, so unrelated chunks never reach the model
 */
@Validated
@ConfigurationProperties("retrieval")
public record RetrievalProperties(@DefaultValue("6") @Min(1) @Max(50) int topK,
		@DefaultValue("0.3") @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity) {
}
