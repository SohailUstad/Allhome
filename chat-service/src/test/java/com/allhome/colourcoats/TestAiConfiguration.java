package com.allhome.colourcoats;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces OpenAI with in-memory fakes (the test profile switches the OpenAI models off). */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfiguration {

	@Bean
	FakeEmbeddingModel embeddingModel() {
		return new FakeEmbeddingModel();
	}

	/** Answers every prompt with a fixed, valid assistant reply. */
	@Bean
	ChatModel chatModel() {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return new ChatResponse(List.of(new Generation(new AssistantMessage(
						"{\"reply\":\"Test reply\",\"handoff\":false,\"handoffReason\":null,\"persona\":null,\"intent\":null,\"lead\":null}"))));
			}

			@Override
			public ChatOptions getDefaultOptions() {
				return ChatOptions.builder().build();
			}
		};
	}

}
