package com.allhome.colourcoats.ingestion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param embeddingBatchSize chunks sent to the embedding model per request (OpenAI accepts up to 2048 inputs)
 */
@Validated
@ConfigurationProperties("ingestion")
public record IngestionProperties(@DefaultValue("32") @Min(1) @Max(2048) int embeddingBatchSize) {
}
