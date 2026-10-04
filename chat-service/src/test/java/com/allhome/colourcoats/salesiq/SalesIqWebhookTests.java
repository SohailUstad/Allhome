package com.allhome.colourcoats.salesiq;

import com.allhome.colourcoats.chat.*;
import com.allhome.colourcoats.salesiq.SalesIqWebhookController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SalesIqWebhookTests {
    final ChatService chat = mock(ChatService.class);
    final ChatRepository repository = mock(ChatRepository.class);

    static final String WEBSITE_MESSAGE = """
            {"handler":"message","operation":"chat","org_id":"123",
             "visitor":{"active_conversation_id":"conv-42","channel":"Website","name":"Visitor 51234","city":"Chennai"},
             "message":{"text":"Do you do Marmorino?"},"request":{"id":"r1"}}
            """;

    MockMvc mvc(long deadlineMs, boolean forwardOnHandoff) {
        when(repository.findLead(any())).thenReturn(Lead.EMPTY);
        var controller = new SalesIqWebhookController(chat, repository, JsonMapper.builder().build(),
                deadlineMs, forwardOnHandoff, "3465000000005", "WELCOME", "FALLBACK", "ASK CONTACT", "TRANSFER", "BUSY");
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrors()).build();
    }

    MockMvc mvc() {
        return mvc(2000, false);
    }

    @Test void websiteMessageGetsBotReplyInSalesIqFormat() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "Yes, Marmorino is one of our textures.", false, List.of()));
        mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reply"))
                .andExpect(jsonPath("$.replies[0]").value("Yes, Marmorino is one of our textures."));
        verify(chat).chat(any(UUID.class), eq("Do you do Marmorino?"), eq(Channel.ZOHO_SALESIQ));
        verify(repository).touchConversation(any(UUID.class), eq(Channel.ZOHO_SALESIQ));
        verify(repository, never()).saveLead(any(), any()); // "Visitor 51234" is a placeholder; geo-IP city is ignored
    }

    /** Live bug 2026-10-04: SalesIQ's message calls got 503 because of a produces=JSON constraint vs its Accept header. */
    @Test void anyAcceptHeaderStillGetsJson() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "ok", false, List.of()));
        for (String accept : List.of("text/plain", "text/html", "*/*", "application/xml")) {
            mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).accept(accept).content(WEBSITE_MESSAGE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.replies[0]").value("ok"));
        }
        mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.TEXT_PLAIN).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("reply"));
    }

    @Test void sameSalesIqConversationMapsToSameStoredConversation() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "ok", false, List.of()));
        var mvc = mvc();
        mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE));
        mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(WEBSITE_MESSAGE.replace("\"chat\"", "\"message\"").replace("Do you do Marmorino?", "In Pune")));
        var ids = ArgumentCaptor.forClass(UUID.class);
        verify(chat, times(2)).chat(ids.capture(), any(), any());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
    }

    @Test void instagramChannelAndPreChatDetailsAreUsed() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "Hi!", false, List.of()));
        mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content("""
                        {"handler":"message","operation":"chat",
                         "visitor":{"active_conversation_id":"ig-7","channel":"Instagram","name":"Priya S","phone":"+91 98450 12345"},
                         "message":{"text":"price?"}}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0]").value("Hi!"));
        verify(chat).chat(any(UUID.class), eq("price?"), eq(Channel.INSTAGRAM));
        var lead = ArgumentCaptor.forClass(Lead.class);
        verify(repository).saveLead(any(), lead.capture());
        assertThat(lead.getValue().name()).isEqualTo("Priya S");
        assertThat(lead.getValue().phone()).isEqualTo("+91 98450 12345");
    }

    @Test void triggerAndValidationPingGetWelcomeWithoutCallingTheBot() throws Exception {
        var mvc = mvc();
        mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"trigger\",\"visitor\":{\"channel\":\"Website\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("reply"))
                .andExpect(jsonPath("$.replies[0]").value("WELCOME"));
        mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0]").value("WELCOME"));
        verifyNoInteractions(chat);
    }

    @Test void attachmentOnlyMessageAsksForText() throws Exception {
        mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content("""
                        {"handler":"message","visitor":{"active_conversation_id":"c"},"message":{"text":""},"attachments":[{}]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0]").value(org.hamcrest.Matchers.containsString("text")));
        verifyNoInteractions(chat);
    }

    @Test void slowBotGetsFallbackWithinDeadlineAndQueuesHuman() throws Exception {
        when(chat.chat(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return new ChatService.Reply(UUID.randomUUID(), "late", false, List.of());
        });
        long start = System.currentTimeMillis();
        mvc(300, false)
                .perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("reply"))
                .andExpect(jsonPath("$.replies[0]").value("FALLBACK ASK CONTACT"));
        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
        verify(repository).saveAssistantMessage(any(), eq("FALLBACK ASK CONTACT"), eq(true), any(), any(), any(), any());
        verify(repository).requestHandoff(any(), contains("time limit"));
    }

    @Test void botFailureStillAnswersSalesIqWith200() throws Exception {
        when(chat.chat(any(), any(), any())).thenThrow(new IllegalStateException("openai down"));
        mvc().perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0]").value("FALLBACK ASK CONTACT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("openai down"))));
        verify(repository).requestHandoff(any(), any());
    }

    @Test void fallbackDoesNotAskForContactAlreadyGiven() throws Exception {
        when(chat.chat(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        var mvc = mvc();
        when(repository.findLead(any())).thenReturn(Lead.EMPTY.merge(null, null,
                new ModelAnswer.LeadDetails("Priya", "9845012345", null, null, null, null, null, null, null, null, null)));
        mvc.perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replies[0]").value("FALLBACK"));
    }

    @Test void handoffForwardsToOperatorDepartmentWhenEnabled() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "Connecting you to a specialist.", true, List.of()));
        mvc(2000, true)
                .perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("forward"))
                .andExpect(jsonPath("$.department").value("3465000000005"))
                .andExpect(jsonPath("$.replies[0]").value("Connecting you to a specialist."));
        verify(repository).markForwarded(any(UUID.class));
    }

    @Test void transferredChatIsNeverAnsweredByTheAiAgain() throws Exception {
        when(repository.isForwarded(any())).thenReturn(true);
        mvc(2000, true)
                .perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("operator_busy"))
                .andExpect(jsonPath("$.replies[0]").value("BUSY"));
        verifyNoInteractions(chat);
        verify(repository).saveUserMessage(any(), eq("Do you do Marmorino?"));   // transcript stays complete
        verify(repository).requestHandoff(any(), contains("no operator"));
    }

    @Test void noHandoffKeepsTheAiChatting() throws Exception {
        when(chat.chat(any(), any(), any())).thenReturn(new ChatService.Reply(UUID.randomUUID(), "Which room is it for?", false, List.of()));
        mvc(2000, true)
                .perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(jsonPath("$.action").value("reply"));
        verify(repository, never()).markForwarded(any());
    }

    @Test void failureWhileForwardingTransfersWithoutAskingForNumber() throws Exception {
        when(chat.chat(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        mvc(2000, true)
                .perform(post("/api/salesiq/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBSITE_MESSAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("forward"))
                .andExpect(jsonPath("$.replies[0]").value("TRANSFER"));
        verify(repository).markForwarded(any(UUID.class));
    }
}
