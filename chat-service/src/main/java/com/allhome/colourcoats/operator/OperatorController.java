package com.allhome.colourcoats.operator;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.Lead;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Operator console: lead list with filters, lead detail with transcript, follow-up marker, CSV export. */
@Controller
@RequestMapping("/leads")
public class OperatorController {
    private com.allhome.colourcoats.flowlog.RunJournal journal = com.allhome.colourcoats.flowlog.RunJournal.transientJournal();
    @org.springframework.beans.factory.annotation.Autowired
    public void observability(com.allhome.colourcoats.flowlog.RunJournal journal) { this.journal = journal; }
    static final int PAGE_SIZE = 25;
    private final LeadRepository leads;
    private final ChatRepository chats;

    public OperatorController(LeadRepository leads, ChatRepository chats) {
        this.leads = leads;
        this.chats = chats;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String persona,
                       @RequestParam(required = false) String intent, @RequestParam(required = false) String channel,
                       @RequestParam(required = false) String followUp, @RequestParam(required = false) String q,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
                       Model model) {
        var filter = LeadFilter.of(status, persona, intent, channel, followUp, q, from, to, sort, page);
        model.addAttribute("filter", filter);
        model.addAttribute("result", leads.search(filter, PAGE_SIZE));
        model.addAttribute("summary", leads.summary());
        model.addAttribute("statuses", List.of("NEW", "ENGAGED", "QUALIFIED"));
        model.addAttribute("personas", Lead.PERSONAS.stream().sorted().toList());
        model.addAttribute("intents", Lead.INTENTS.stream().sorted().toList());
        model.addAttribute("channels", Channel.values());
        journal.audit("operator.leads.viewed", java.util.Map.of("page", filter.page()));
        return "leads";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        var lead = leads.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation"));
        model.addAttribute("lead", lead);
        model.addAttribute("messages", leads.messages(id));
        model.addAttribute("whatsapp", whatsappNumber(lead.phone()));
        model.addAttribute("personas", Lead.PERSONAS.stream().filter(p -> !p.equals("UNKNOWN")).sorted().toList());
        journal.audit("operator.transcript.viewed", java.util.Map.of("conversation_id", id));
        return "lead-detail";
    }

    /** Operator correction when the bot classified the visitor wrongly (or not at all); status is recomputed. */
    @PostMapping("/{id}/persona")
    public String setPersona(@PathVariable UUID id, @RequestParam String persona, RedirectAttributes redirect) {
        if (!Lead.PERSONAS.contains(persona) || "UNKNOWN".equals(persona)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown persona");
        }
        leads.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation"));
        journal.atomic(() -> {
            chats.saveLead(id, chats.findLead(id).merge(persona, null, null));
            journal.audit("operator.persona.changed", java.util.Map.of("conversation_id", id, "persona", persona));
        });
        redirect.addFlashAttribute("notice", "Persona updated.");
        return "redirect:/leads/" + id;
    }

    @PostMapping("/{id}/handled")
    public String toggleHandled(@PathVariable UUID id, RedirectAttributes redirect) {
        journal.atomic(() -> {
            if (!leads.toggleHandled(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation");
            journal.audit("operator.handoff.handled", java.util.Map.of("conversation_id", id));
        });
        redirect.addFlashAttribute("notice", "Follow-up status updated.");
        return "redirect:/leads/" + id;
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status, @RequestParam(required = false) String persona,
                                         @RequestParam(required = false) String intent, @RequestParam(required = false) String channel,
                                         @RequestParam(required = false) String followUp, @RequestParam(required = false) String q,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String sort) {
        var filter = LeadFilter.of(status, persona, intent, channel, followUp, q, from, to, sort, 1);
        var csv = new StringBuilder("﻿"); // BOM so Excel opens UTF-8 (₹, Hindi names) correctly
        List<Function<LeadRepository.LeadRow, Object>> columns = List.of(
                LeadRepository.LeadRow::updatedAt, LeadRepository.LeadRow::status, LeadRepository.LeadRow::persona,
                LeadRepository.LeadRow::intent, LeadRepository.LeadRow::name, LeadRepository.LeadRow::phone,
                LeadRepository.LeadRow::email, LeadRepository.LeadRow::city, LeadRepository.LeadRow::projectType,
                LeadRepository.LeadRow::spaces, LeadRepository.LeadRow::finishInterest, LeadRepository.LeadRow::areaSize,
                LeadRepository.LeadRow::timeline, LeadRepository.LeadRow::budget, LeadRepository.LeadRow::callbackTime,
                LeadRepository.LeadRow::channel, r -> r.needsFollowUp() ? "yes" : "no", LeadRepository.LeadRow::handoffReason,
                LeadRepository.LeadRow::conversationId);
        csv.append("updated_at,status,persona,intent,name,phone,email,city,project_type,spaces,finish_interest,area_size,"
                + "timeline,budget,callback_time,channel,needs_follow_up,handoff_reason,conversation_id\r\n");
        var rows = leads.export(filter, 10_000);
        for (var row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) csv.append(',');
                csv.append(csvCell(columns.get(i).apply(row)));
            }
            csv.append("\r\n");
        }
        String file = "colourcoats-leads-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".csv";
        journal.audit("operator.leads.exported", java.util.Map.of("row_count", rows.size()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quotes every cell; neutralises spreadsheet formulas typed by visitors ("=HYPERLINK(...)"). */
    static String csvCell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** wa.me needs digits with country code; bare 10-digit Indian mobiles get 91. */
    static String whatsappNumber(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 11 && digits.startsWith("0")) return "91" + digits.substring(1);
        return digits.length() >= 11 && digits.length() <= 15 ? digits : null;
    }
}
