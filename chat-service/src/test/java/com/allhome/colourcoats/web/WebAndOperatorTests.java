package com.allhome.colourcoats.web;

import com.allhome.colourcoats.chat.ChatController;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.ChatSource;
import com.allhome.colourcoats.operator.LeadFilter;
import com.allhome.colourcoats.operator.LeadRepository;
import com.allhome.colourcoats.operator.OperatorController;
import com.allhome.colourcoats.web.PageController;
import com.allhome.colourcoats.security.SecurityConfiguration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Renders the real Thymeleaf templates through the real security rules (repositories mocked). */
@WebMvcTest(controllers = {PageController.class, OperatorController.class, ChatController.class},
        properties = {"operator.username=op", "operator.password=secret-pass", "salesiq.widget-code=siqTESTCODE"})
@Import(SecurityConfiguration.class)
class WebAndOperatorTests {
    @Autowired MockMvc mvc;
    @MockitoBean LeadRepository leads;
    @MockitoBean ChatService chatService;
    @MockitoBean ChatRepository chatRepository;

    static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-04T10:15:30+05:30");

    static LeadRepository.LeadRow row(String name, String phone, String status, boolean followUp) {
        return new LeadRepository.LeadRow(ID, "INSTAGRAM", NOW, NOW, followUp ? NOW : null,
                followUp ? "Wants lime wash price per sq ft" : null, null, status, "HOMEOWNER", "PLANNING_PROJECT",
                name, phone, null, "Bangalore", "renovation", "living room", "lime wash", "3BHK", "next month",
                null, null, 6, "Can you share the price?", followUp ? NOW : null);
    }

    @Test void homePageIsPublicWithSalesIqWidgetAndDirectChat() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("https://salesiq.zohopublic.in/widget?wc=siqTESTCODE")))
                .andExpect(content().string(containsString("id=\"zsiqscript\"")))
                .andExpect(content().string(containsString("id=\"chatPanel\"")))
                .andExpect(content().string(containsString("/js/chat.js")))
                .andExpect(content().string(containsString("window.CC_OPENER = \"Hi there! I")))
                .andExpect(content().string(containsString("Aira")));
    }

    /** Live log 2026-10-04: browsers asking for /favicon.ico got 503 and an ERROR line from the API error handler. */
    @Test void missingStaticFilesAre404AndCommonFilesArePublic() throws Exception {
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Disallow: /leads")));
    }

    @Test void leadConsoleRequiresLogin() throws Exception {
        // Browsers (Accept: text/html) are sent to the login page; anything else gets a plain 401.
        mvc.perform(get("/leads").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/leads/" + ID).accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/leads/export.csv")).andExpect(status().isUnauthorized());
        verifyNoInteractions(leads);
    }

    @Test void apiEndpointsWithPersonalDataReturn401NotRedirect() throws Exception {
        mvc.perform(get("/api/chat/" + ID + "/messages")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ingestions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void publicChatApiWorksWithoutLoginOrCsrfToken() throws Exception {
        when(chatService.chat(any(), any(), any())).thenReturn(new ChatService.Reply(ID, "Hello!", false, List.of()));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"channel\":\"WEB_CHAT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Hello!"));
    }

    @Test void formLoginWithConfiguredOperatorCredentials() throws Exception {
        mvc.perform(post("/login").param("username", "op").param("password", "secret-pass").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/leads"));
        mvc.perform(post("/login").param("username", "op").param("password", "wrong").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Lead console")));
    }

    @Test void leadListRendersRowsKpisAndPassesSanitisedFilters() throws Exception {
        when(leads.search(any(), anyInt())).thenReturn(new LeadRepository.Page(
                List.of(row("Priya", "9845012345", "QUALIFIED", true)), 1, 1, 25));
        when(leads.summary()).thenReturn(new LeadRepository.Summary(12, 3, 2, 5));

        mvc.perform(get("/leads").with(user("op").roles("OPERATOR"))
                        .param("status", "QUALIFIED").param("persona", "HOMEOWNER").param("channel", "DROP TABLE")
                        .param("followUp", "needed").param("q", "priya").param("from", "2026-10-01").param("sort", "evil"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Priya")))
                .andExpect(content().string(containsString("9845012345")))
                .andExpect(content().string(containsString("lime wash · living room · renovation")))
                .andExpect(content().string(containsString("Follow up")))
                .andExpect(content().string(containsString("Qualified leads")));

        var filter = ArgumentCaptor.forClass(LeadFilter.class);
        verify(leads).search(filter.capture(), eq(25));
        assertThat(filter.getValue().status()).isEqualTo("QUALIFIED");
        assertThat(filter.getValue().persona()).isEqualTo("HOMEOWNER");
        assertThat(filter.getValue().channel()).isNull();      // unknown value dropped, never reaches SQL
        assertThat(filter.getValue().sort()).isEqualTo("updated");
        assertThat(filter.getValue().followUp()).isEqualTo("needed");
        assertThat(filter.getValue().q()).isEqualTo("priya");
        assertThat(filter.getValue().from()).hasToString("2026-10-01");
    }

    @Test void emptyListShowsEmptyState() throws Exception {
        when(leads.search(any(), anyInt())).thenReturn(new LeadRepository.Page(List.of(), 0, 1, 25));
        when(leads.summary()).thenReturn(new LeadRepository.Summary(0, 0, 0, 0));
        mvc.perform(get("/leads").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("No conversations match")));
    }

    @Test void leadDetailShowsProfileMissingFieldsTranscriptAndActions() throws Exception {
        when(leads.find(ID)).thenReturn(Optional.of(row(null, "98450 12345", "ENGAGED", true)));
        when(leads.messages(ID)).thenReturn(List.of(
                new LeadRepository.Message("USER", "<script>alert(1)</script> price?", false, List.of(), NOW, null, null),
                new LeadRepository.Message("ASSISTANT", "A specialist will share a quote.", true,
                        List.of(new ChatSource("c1", "https://www.colourcoats.com/#svc-limewash", "I · Wall Textures", 0.6)), NOW,
                        "gpt-4.1-mini-2025-04-14", 3)));
        mvc.perform(get("/leads/" + ID).with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Anonymous visitor")))
                .andExpect(content().string(containsString("Still needed:")))
                .andExpect(content().string(containsString("Name")))
                .andExpect(content().string(containsString("https://wa.me/919845012345")))
                .andExpect(content().string(containsString("Wants lime wash price per sq ft")))
                .andExpect(content().string(containsString("Mark as handled")))
                .andExpect(content().string(containsString("gpt-4.1-mini-2025-04-14 · prompt v3")))
                .andExpect(content().string(containsString("Transferred to SalesIQ operator")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test void operatorCanCorrectPersonaAndStatusIsRecomputed() throws Exception {
        when(leads.find(ID)).thenReturn(Optional.of(row("Sohail", "+918898542558", "ENGAGED", true)));
        when(chatRepository.findLead(ID)).thenReturn(com.allhome.colourcoats.chat.Lead.EMPTY.merge(null, null,
                new com.allhome.colourcoats.chat.ModelAnswer.LeadDetails("Sohail", "+918898542558", null, "Ahmedabad",
                        null, null, "textures", null, null, null, null)));
        mvc.perform(post("/leads/" + ID + "/persona").param("persona", "PARTNER_PROSPECT")
                        .with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/leads/" + ID));
        var saved = ArgumentCaptor.forClass(com.allhome.colourcoats.chat.Lead.class);
        verify(chatRepository).saveLead(eq(ID), saved.capture());
        assertThat(saved.getValue().persona()).isEqualTo("PARTNER_PROSPECT");
        assertThat(saved.getValue().status()).isEqualTo("QUALIFIED");

        mvc.perform(post("/leads/" + ID + "/persona").param("persona", "UNKNOWN").with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/leads/" + ID + "/persona").param("persona", "HOMEOWNER").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden()); // no CSRF token
    }

    @Test void unknownLeadIs404() throws Exception {
        when(leads.find(any())).thenReturn(Optional.empty());
        mvc.perform(get("/leads/" + UUID.randomUUID()).with(user("op").roles("OPERATOR"))).andExpect(status().isNotFound());
    }

    @Test void markHandledNeedsCsrfAndRedirectsBack() throws Exception {
        when(leads.toggleHandled(ID)).thenReturn(true);
        mvc.perform(post("/leads/" + ID + "/handled").with(user("op").roles("OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/leads/" + ID + "/handled").with(user("op").roles("OPERATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/leads/" + ID));
        verify(leads, times(1)).toggleHandled(ID);
    }

    @Test void csvExportNeutralisesFormulasAndKeepsUtf8() throws Exception {
        when(leads.export(any(), anyInt())).thenReturn(List.of(row("=HYPERLINK(\"http://evil\")", "+919845012345", "QUALIFIED", false)));
        var body = mvc.perform(get("/leads/export.csv").with(user("op").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("colourcoats-leads-")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿updated_at,status,persona");
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").contains("\"'+919845012345\"");
    }
}
