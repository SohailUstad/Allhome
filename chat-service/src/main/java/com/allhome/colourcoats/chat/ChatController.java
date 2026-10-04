package com.allhome.colourcoats.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private com.allhome.colourcoats.flowlog.RunJournal journal = com.allhome.colourcoats.flowlog.RunJournal.transientJournal();
    @org.springframework.beans.factory.annotation.Autowired
    public void observability(com.allhome.colourcoats.flowlog.RunJournal journal) { this.journal = journal; }
    private final ChatService chatService;
    private final ChatRepository repository;

    public ChatController(ChatService chatService, ChatRepository repository) {
        this.chatService = chatService;
        this.repository = repository;
    }

    /** Omit conversationId to start a new conversation; send the returned id on every later turn. Channel defaults to ZOHO_SALESIQ. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatService.Reply chat(@Valid @RequestBody Request request) {
        UUID conversationId = request.conversationId() == null ? UUID.randomUUID() : request.conversationId();
        return chatService.chat(conversationId, request.message().strip(),
                request.channel() == null ? Channel.ZOHO_SALESIQ : request.channel());
    }

    @GetMapping("/{conversationId}/messages")
    public List<ChatRepository.StoredMessage> history(@PathVariable UUID conversationId) {
        if (!repository.exists(conversationId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation");
        var messages = repository.recentMessages(conversationId, 500);
        journal.audit("operator.transcript.viewed", java.util.Map.of("conversation_id", conversationId));
        return messages;
    }

    public record Request(UUID conversationId, @NotBlank @Size(max = 2000) String message, Channel channel) {}
}
