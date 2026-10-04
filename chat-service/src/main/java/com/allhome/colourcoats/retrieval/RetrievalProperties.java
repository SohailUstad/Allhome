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
 * @param candidates results each search (vector, keyword) contributes before fusion; more gives fusion more to work
 * with, at the cost of a slightly slower query
 * @param minSimilarity cosine similarity (0..1) a vector match needs, so unrelated chunks never reach the model;
 * keyword matches are kept regardless, since they share exact words with the question
 */
@Validated
@ConfigurationProperties("retrieval")
public record RetrievalProperties(@DefaultValue("6") @Min(1) @Max(50) int topK,
		@DefaultValue("20") @Min(1) @Max(200) int candidates,
		@DefaultValue("0.3") @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity) {
}
