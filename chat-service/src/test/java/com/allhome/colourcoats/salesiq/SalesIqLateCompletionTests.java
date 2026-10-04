package com.allhome.colourcoats.salesiq;

import com.allhome.colourcoats.chat.*;
import com.allhome.colourcoats.retrieval.KnowledgeSearch;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SalesIqLateCompletionTests {
    @Test void modelIgnoringInterruptCannotWriteAfterFallback() throws Exception {
        var repository = mock(ChatRepository.class);
        when(repository.findLead(any())).thenReturn(Lead.EMPTY);
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        var modelEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerFinished = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            modelEntered.countDown();
            boolean done = false;
            while (!done) {
                try { done = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate a provider client ignoring cancellation. */ }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"reply\":\"late answer\",\"handoff\":false}"))));
        });
        var service = new ChatService(ChatClient.builder(model), mock(KnowledgeSearch.class), repository,
                TransactionOperations.withoutTransaction(), SystemPrompts.fixed("test system"), 20, true, "TRANSFER") {
            @Override public Reply chat(java.util.UUID id, String message, Channel channel) {
                try { return super.chat(id, message, channel); }
                finally { workerFinished.countDown(); }
            }
        };
        var controller = new SalesIqWebhookController(service, repository, new SalesIqSignatureVerifier(""), JsonMapper.builder().build(),
                500, true, "", "WELCOME", "FALLBACK", "CONTACT", "TRANSFER", "BUSY");
        try {
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content("""
                    {"handler":"message","visitor":{"active_conversation_id":"late-test"},"message":{"text":"hello"}}
                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("forward"))
                    .andExpect(jsonPath("$.replies[0]").value("TRANSFER"));
            assertThat(modelEntered.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(workerFinished.await(2, TimeUnit.SECONDS)).isTrue();
            verify(repository, times(1)).saveUserMessage(any(), eq("hello"));
            verify(repository, times(1)).saveAssistantMessage(any(), eq("TRANSFER"), eq(true), any(), any(), any(), any());
            verify(repository, never()).saveAssistantMessage(any(), eq("late answer"), anyBoolean(), any(), any(), any(), any(), any());
            verify(repository, never()).saveLead(any(), any());
        } finally { release.countDown(); controller.stop(); }
    }
}
