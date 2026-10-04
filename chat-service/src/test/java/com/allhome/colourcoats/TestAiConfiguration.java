package com.allhome.colourcoats;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces OpenAI with in-memory fakes (the test profile switches the OpenAI models off). */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfiguration {

	@Bean
	FakeEmbeddingModel embeddingModel() {
		return new FakeEmbeddingModel();
	}

}
