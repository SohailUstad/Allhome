package com.allhome.colourcoats.chat;

import com.allhome.colourcoats.chat.*;
import com.allhome.colourcoats.ingestion.ApiErrors;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatTests {
    final ChatModel model = mock(ChatModel.class);
    final VectorStore store = mock(VectorStore.class);
    final ChatRepository repository = mock(ChatRepository.class);
    final KeywordRetriever keywords = mock(KeywordRetriever.class);
    final MockMvc mvc = mvc();

    MockMvc mvc() {
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(repository.findLead(any())).thenReturn(Lead.EMPTY);
        try {
            var service = new ChatService(ChatClient.builder(model), store, keywords, repository,
                    new ByteArrayResource("SYSTEM PROMPT {not a template}".getBytes()), 20, 6, 0.3, 3, true, "Connecting you now.");
            return MockMvcBuilders.standaloneSetup(new ChatController(service, repository))
                    .setControllerAdvice(new ApiErrors()).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void modelReturns(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @Test void answersWithKnowledgeHistoryAndLeadProfileThenPersistsTurn() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.recentMessages(id, 20)).thenReturn(List.of(
                new ChatRepository.StoredMessage("USER", "We are repainting our villa in Pune", null),
                new ChatRepository.StoredMessage("ASSISTANT", "Lovely! Interior or exterior?", null)));
        when(repository.findLead(id)).thenReturn(Lead.EMPTY.merge("HOMEOWNER", "PLANNING_PROJECT",
                new ModelAnswer.LeadDetails(null, null, null, "Pune", "repaint", null, null, null, null, null, null)));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("c1").text("Anti-fungal systems for the monsoon-facing side.").score(0.61)
                        .metadata(Map.of("chunk_id", "abc", "url", "https://www.colourcoats.com/",
                                "source_urls", List.of("https://www.colourcoats.com/#svc-painting"),
                                "heading_path", List.of("IV · Painting Works", "Overview"))).build()));
        modelReturns("""
                {"reply":"We offer anti-fungal systems for the monsoon-facing side.","handoff":false,"handoffReason":null,
                 "persona":"HOMEOWNER","intent":"PLANNING_PROJECT",
                 "lead":{"city":null,"projectType":"villa repaint","spaces":"exterior walls"}}
                """);

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + id + "\",\"message\":\" exterior walls \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id.toString()))
                .andExpect(jsonPath("$.reply").value("We offer anti-fungal systems for the monsoon-facing side."))
                .andExpect(jsonPath("$.handoff").value(false))
                .andExpect(jsonPath("$.persona").doesNotExist())
                .andExpect(jsonPath("$.sources[0].url").value("https://www.colourcoats.com/#svc-painting"))
                .andExpect(jsonPath("$.sources[0].section").value("IV · Painting Works > Overview"));

        var search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(search.capture());
        assertThat(search.getValue().getQuery()).isEqualTo("We are repainting our villa in Pune\nexterior walls");
        assertThat(search.getValue().getTopK()).isEqualTo(6);

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        List<Message> messages = prompt.getValue().getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("SYSTEM PROMPT {not a template}");
        assertThat(messages.get(1).getText()).isEqualTo("We are repainting our villa in Pune");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(3).getText()).contains("Anti-fungal systems", "city: Pune", "persona: HOMEOWNER",
                "phone: unknown", "channel: ZOHO_SALESIQ", "Visitor message:\nexterior walls", "handoffReason");

        var order = inOrder(repository, model);
        order.verify(repository).touchConversation(id, Channel.ZOHO_SALESIQ);
        order.verify(repository).saveUserMessage(id, "exterior walls");
        order.verify(model).call(any(Prompt.class));
        order.verify(repository).saveAssistantMessage(eq(id), eq("We offer anti-fungal systems for the monsoon-facing side."),
                eq(false), argThat(s -> s.size() == 1), any(), any(), any());
        var lead = ArgumentCaptor.forClass(Lead.class);
        order.verify(repository).saveLead(eq(id), lead.capture());
        assertThat(lead.getValue().city()).isEqualTo("Pune");            // known detail kept when model returns null
        assertThat(lead.getValue().projectType()).isEqualTo("villa repaint");
        assertThat(lead.getValue().spaces()).isEqualTo("exterior walls");
        assertThat(lead.getValue().status()).isEqualTo("ENGAGED");
        verify(repository, never()).requestHandoff(any(), any());
    }

    @Test void liveTransferReplyDropsQuestionAndTranscriptMatchesWhatVisitorSaw() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        modelReturns("{\"reply\":\"I don't have info about our founder here, but I can connect you with someone who can share more. "
                + "Meanwhile, what kind of project are you thinking about?\",\"handoff\":true,\"handoffReason\":\"Founder question\"}");
        String expected = "I don't have info about our founder here, but I can connect you with someone who can share more.";
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"who is the founder?\",\"channel\":\"ZOHO_SALESIQ\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handoff").value(true))
                .andExpect(jsonPath("$.reply").value(expected));
        verify(repository).saveAssistantMessage(any(), eq(expected), eq(true), any(), any(), any(), any());
    }

    @Test void webChatHandoffKeepsItsQuestionBecauseNobodyTakesOver() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        modelReturns("{\"reply\":\"A specialist will share a quote. What's your name and number?\",\"handoff\":true}");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"price?\",\"channel\":\"WEB_CHAT\"}"))
                .andExpect(jsonPath("$.reply").value("A specialist will share a quote. What's your name and number?"));
    }

    @Test void unansweredQuestionIsHandedOffAndContactQualifiesLead() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        modelReturns("""
                ```json
                {"reply":"I don't have pricing information. A specialist will call you.","handoff":true,
                 "handoffReason":"Asked for exterior paint price","persona":"homeowner","intent":"READY_TO_ENGAGE",
                 "lead":{"name":"Ravi","phone":"98450 00000","city":"Bengaluru","projectType":"new home"}}
                ```
                """);
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"price per sq ft?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handoff").value(true))
                .andExpect(jsonPath("$.reply").value("I don't have pricing information. A specialist will call you."));
        verify(repository).saveAssistantMessage(any(), any(), eq(true), any(), any(), any(), any());
        verify(repository).requestHandoff(any(UUID.class), eq("Asked for exterior paint price"));
        var lead = ArgumentCaptor.forClass(Lead.class);
        verify(repository).saveLead(any(), lead.capture());
        assertThat(lead.getValue().persona()).isEqualTo("HOMEOWNER");
        assertThat(lead.getValue().status()).isEqualTo("QUALIFIED");
    }

    @Test void unparseableModelOutputIsNeverShownAndHandsOff() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        modelReturns("Sure! Our exterior paint costs Rs 40/sqft");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"price?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handoff").value(true))
                .andExpect(jsonPath("$.reply").value(not(containsString("Rs 40"))));
        verify(repository).requestHandoff(any(UUID.class), any());
        verify(repository).saveLead(any(), eq(Lead.EMPTY));
    }

    @Test void mergesKeywordMatchesWithVectorMatchesWithoutDuplicates() throws Exception {
        var shared = Document.builder().id("row-1").text("Lime plaster, Calceterra, Marmorino").score(0.5)
                .metadata(Map.of("chunk_id", "marmorino-chunk", "url", "https://www.colourcoats.com/#svc-limewash")).build();
        var sameChunkOtherRow = Document.builder().id("row-2").text("Lime plaster, Calceterra, Marmorino")
                .metadata(Map.of("chunk_id", "marmorino-chunk", "url", "https://www.colourcoats.com/#svc-limewash")).build();
        var keywordOnly = Document.builder().id("row-3").text("ColourCoats Kolkata Experience Centre, Tiljala")
                .metadata(Map.of("chunk_id", "kolkata-chunk", "url", "https://www.colourcoats.com/#studio")).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(shared));
        when(keywords.search(any(), eq(3))).thenReturn(List.of(sameChunkOtherRow, keywordOnly));
        modelReturns("{\"reply\":\"Yes, Marmorino is one of our wall textures.\",\"handoff\":false}");

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Marmorino in Kolkata?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].chunkId").value("marmorino-chunk"))
                .andExpect(jsonPath("$.sources[1].chunkId").value("kolkata-chunk"));
        verify(keywords).search("Marmorino in Kolkata?", 3);
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getLast().getText()).contains("Tiljala", "Marmorino");
    }

    @Test void startsNewConversationWhenIdOmitted() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        modelReturns("{\"reply\":\"Hi!\",\"handoff\":false}");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.sources").isEmpty());
        verify(repository).touchConversation(any(UUID.class), eq(Channel.ZOHO_SALESIQ));
    }

    @Test void invalidInputNeverCallsProviders() throws Exception {
        for (String body : List.of("{}", "{\"message\":\" \"}", "{\"message\":\"" + "a".repeat(2001) + "\"}",
                "{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}", "{",
                "{\"message\":\"hi\",\"channel\":\"FAX\"}")) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
        verify(model, never()).call(any(Prompt.class));
        verify(repository, never()).touchConversation(any(), any());
    }

    @Test void historyReturns404ForUnknownConversationAnd400ForBadId() throws Exception {
        mvc.perform(get("/api/chat/" + UUID.randomUUID() + "/messages")).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/nope/messages")).andExpect(status().isBadRequest());
    }

    @Test void providerFailureKeepsUserMessageAndHidesDetails() throws Exception {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret provider details"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("secret"))));
        verify(repository).saveUserMessage(any(), eq("hello"));
        verify(repository, never()).saveAssistantMessage(any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(repository, never()).saveLead(any(), any());
    }

    @Test void leadMergeKeepsKnownValuesAndRejectsUnknownLabels() {
        var first = Lead.EMPTY.merge("ARCHITECT_OR_DESIGNER", "RESEARCHING",
                new ModelAnswer.LeadDetails(null, null, "a@b.in", "Mumbai", null, null, null, null, null, null, null));
        var second = first.merge("ALIEN", "UNKNOWN",
                new ModelAnswer.LeadDetails("  ", null, null, "null", "office", null, null, null, "next month", null, null));
        assertThat(second.persona()).isEqualTo("ARCHITECT_OR_DESIGNER");
        assertThat(second.intent()).isEqualTo("RESEARCHING");
        assertThat(second.email()).isEqualTo("a@b.in");
        assertThat(second.city()).isEqualTo("Mumbai");
        assertThat(second.timeline()).isEqualTo("next month");
        assertThat(second.status()).isEqualTo("ENGAGED");          // no name or phone yet
        var complete = second.merge(null, null,
                new ModelAnswer.LeadDetails("Anita", "9876543210", null, null, null, null, null, null, null, null, null));
        assertThat(complete.status()).isEqualTo("QUALIFIED");
        assertThat(complete.merge("UNKNOWN", null, null).status()).isEqualTo("QUALIFIED"); // UNKNOWN never overwrites
        assertThat(Lead.EMPTY.merge(null, null, new ModelAnswer.LeadDetails("Anita", "9876543210", null, "Pune",
                "repaint", null, null, null, null, null, null)).status()).isEqualTo("ENGAGED"); // persona still unknown
        assertThat(Lead.EMPTY.status()).isEqualTo("NEW");
    }
}
