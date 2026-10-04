package com.allhome.colourcoats.salesiq;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.ModelAnswer;
import com.allhome.colourcoats.flowlog.FlowLog;
import com.allhome.colourcoats.flowlog.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * Zoho SalesIQ Zobot webhook (request/response format 2.0). Register
 * {@code https://<public-host>/api/salesiq/webhook} as the bot's webhook URL.
 *
 * SalesIQ waits at most 5 seconds, so every reply is time-boxed: if the pipeline misses the deadline (or fails), the visitor gets a holding message,
 * the conversation is queued for a human, and SalesIQ always receives a valid 200 response.
 *
 * Requests are not authenticated: SalesIQ's webhook signing ("Secure your webhook") is not set up (README, Limitations).
 */
@RestController
@RequestMapping("/api/salesiq/webhook")
public class SalesIqWebhookController {
    // SalesIQ fills anonymous visitors with placeholder names such as "Visitor 51234".
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SalesIqWebhookController.class);
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("(?i)^(visitor|guest)\\b.*");

    private final ChatService chatService;
    private final ChatRepository repository;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final long deadlineMs;
    private final boolean forwardOnHandoff;
    private final String department;
    private final String opener;
    private final String fallbackMessage;
    private final String contactRequest;
    private final String transferMessage;
    private final String busyMessage;
    private TransactionOperations transaction = TransactionOperations.withoutTransaction();

    /** Saves that belong together (reply + handoff + forward marker) commit together. */
    @Autowired public void transactions(TransactionOperations transaction) {
        this.transaction = transaction;
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }

    public SalesIqWebhookController(ChatService chatService, ChatRepository repository, JsonMapper json,
                                    @Value("${salesiq.response-deadline-ms:3800}") long deadlineMs,
                                    @Value("${salesiq.forward-on-handoff:true}") boolean forwardOnHandoff,
                                    @Value("${salesiq.department-id:}") String department,
                                    @Value("${salesiq.opener}") String opener,
                                    @Value("${salesiq.fallback-message}") String fallbackMessage,
                                    @Value("${salesiq.contact-request}") String contactRequest,
                                    @Value("${salesiq.transfer-message}") String transferMessage,
                                    @Value("${salesiq.busy-message}") String busyMessage) {
        this.chatService = chatService;
        this.repository = repository;
        this.json = json;
        this.deadlineMs = deadlineMs;
        this.forwardOnHandoff = forwardOnHandoff;
        this.department = department;
        if (opener == null || opener.isBlank()) throw new IllegalArgumentException("salesiq.opener is empty");
        this.opener = opener;
        this.fallbackMessage = fallbackMessage;
        this.contactRequest = contactRequest;
        this.transferMessage = transferMessage;
        this.busyMessage = busyMessage;
    }

    // No "produces": SalesIQ's Accept header must never cause a 406; the body is always JSON (see ok()).
    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody(required = false) byte[] rawBody) {
        try (var scope = TraceContext.open()) {
            long started = System.nanoTime();
            String body = rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);
            logReceived(body);
            try {
                var response = respond(body);
                FlowLog.log("salesiq.response", "http_status", response.getStatusCode().value(),
                        "payload", response.getBody(), "duration_ms", elapsedMs(started));
                return response;
            } catch (RuntimeException e) {
                FlowLog.log("salesiq.response", "error", e.toString(), "duration_ms", elapsedMs(started));
                throw e;
            }
        }
    }

    /** Step 1 of the flow log: the exact payload SalesIQ sent, plus its own IDs for grouping. */
    private void logReceived(String body) {
        if (!FlowLog.enabled()) return;
        try {
            JsonNode request = body.isBlank() ? json.createObjectNode() : json.readTree(body);
            JsonNode visitor = request.path("visitor");
            FlowLog.log("salesiq.request", "handler", text(request.path("handler")),
                    "salesiq_request_id", text(request.path("request").path("id")),
                    "salesiq_conversation_id", text(visitor.path("active_conversation_id")),
                    // Same derivation as the stored conversation; omitted when SalesIQ sent no visitor IDs (it would be random).
                    "conversation_id", Stream.of("active_conversation_id", "uuid", "visitid").anyMatch(f -> text(visitor.path(f)) != null)
                            ? conversationId(visitor).toString() : null,
                    "payload", json.convertValue(request, Map.class));
        } catch (RuntimeException e) {
            FlowLog.log("salesiq.request", "payload_raw", body, "parse_error", e.getClass().getSimpleName());
        }
    }

    private ResponseEntity<Map<String, Object>> respond(String body) {
        JsonNode request;
        try {
            request = body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (RuntimeException e) {
            TraceContext.outcome("invalid_payload");
            return ok(reply(List.of(opener)));
        }
        String handler = text(request.path("handler"));
        if (!"message".equals(handler)) {
            TraceContext.outcome("greeting");
            // "trigger" (visitor landed), "context", SalesIQ's validation ping, or anything unknown: greet.
            return ok(reply(List.of(opener)));
        }
        return ok(onMessage(request));
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> onMessage(JsonNode request) {
        try (var scope = TraceContext.open()) {
            return processMessage(request);
        }
    }

    private Map<String, Object> processMessage(JsonNode request) {
        long started = System.nanoTime();
        JsonNode visitor = request.path("visitor");
        String message = text(request.path("message").path("text"));
        if (message == null) {
            TraceContext.outcome("unsupported_message");
            return reply(List.of("I can only read text messages at the moment. Could you type your question?"));
        }
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }

        UUID conversationId = conversationId(visitor);
        Channel channel = "instagram".equalsIgnoreCase(text(visitor.path("channel"))) ? Channel.INSTAGRAM : Channel.ZOHO_SALESIQ;
        var context = TraceContext.current();
        context.bind(conversationId, channel.name());
        repository.touchConversation(conversationId, channel);
        seedLeadFromVisitor(conversationId, visitor);
        repository.saveUserMessage(conversationId, message);

        // Once a chat is handed to a human, the AI never answers it again. SalesIQ only calls the bot again if no
        // operator took the chat, so let SalesIQ's own "operators busy / leave a message" flow handle it.
        if (forwardOnHandoff && repository.isForwarded(conversationId)) {
            transaction.executeWithoutResult(status -> {
                repository.saveAssistantMessage(conversationId, busyMessage, true, List.of(), null, null, null);
                repository.requestHandoff(conversationId, "Visitor wrote again after the transfer; no operator picked up the chat.");
            });
            TraceContext.outcome("operator_busy");
            return action("operator_busy", busyMessage);
        }

        context.completion.defer();
        if (elapsedMs(started) >= deadlineMs) {
            context.completion.abandon();
            return fallback(conversationId, "Bot reply exceeded the SalesIQ time limit", "fallback_timeout");
        }
        String finalMessage = message;
        var future = executor.submit(TraceContext.propagate(() -> chatService.chat(conversationId, finalMessage, channel)));
        try {
            long remaining = deadlineMs - elapsedMs(started);
            if (remaining <= 0) throw new TimeoutException();
            var result = future.get(remaining, TimeUnit.MILLISECONDS);
            transaction.executeWithoutResult(status -> {
                context.completion.accept();
                if (result.handoff() && forwardOnHandoff) repository.markForwarded(conversationId);
            });
            if (result.handoff() && forwardOnHandoff) {
                return forward(result.reply());
            }
            return reply(List.of(result.reply()));
        } catch (TimeoutException e) {
            context.completion.abandon();
            future.cancel(true);
            return fallback(conversationId, "Bot reply exceeded the SalesIQ time limit", "fallback_timeout");
        } catch (Exception e) {
            context.completion.abandon();
            future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("SalesIQ reply failed", e);
            return fallback(conversationId, "Bot reply failed (" + rootCause(e) + ")", "fallback_error");
        }
    }

    /** The visitor is never left without an answer: a human follows up on what the bot could not answer in time. */
    private Map<String, Object> fallback(UUID conversationId, String reason, String outcome) {
        // Forwarding: an operator takes over live, so no contact request; otherwise ask for a number to call back.
        String message = forwardOnHandoff ? transferMessage : fallbackMessage;
        try {
            if (!forwardOnHandoff && repository.findLead(conversationId).phone() == null) message = fallbackMessage + " " + contactRequest;
            String selected = message;
            transaction.executeWithoutResult(status -> {
                repository.saveAssistantMessage(conversationId, selected, true, List.of(), null, null, null);
                repository.requestHandoff(conversationId, reason + "; follow up on the visitor's last message.");
                if (forwardOnHandoff) repository.markForwarded(conversationId);
            });
        } catch (RuntimeException e) {
            log.error("SalesIQ fallback could not be saved", e);
            outcome = "fallback_persistence_failed";
        }
        TraceContext.outcome(outcome);
        return forwardOnHandoff ? forward(message) : reply(List.of(message));
    }

    /** Stable per SalesIQ conversation, so every turn of one chat lands in the same stored conversation. */
    static UUID conversationId(JsonNode visitor) {
        for (String field : List.of("active_conversation_id", "uuid", "visitid")) {
            String value = text(visitor.path(field));
            if (value != null) return UUID.nameUUIDFromBytes(("salesiq:" + field + ":" + value).getBytes(StandardCharsets.UTF_8));
        }
        return UUID.randomUUID();
    }

    /** Pre-chat form details are visitor-provided, so they count; SalesIQ's geo-IP city is not the project city. */
    private void seedLeadFromVisitor(UUID conversationId, JsonNode visitor) {
        String name = text(visitor.path("name"));
        if (name != null && PLACEHOLDER_NAME.matcher(name).matches()) name = null;
        String email = text(visitor.path("email"));
        String phone = text(visitor.path("phone"));
        if (name == null && email == null && phone == null) return;
        var known = repository.findLead(conversationId);
        repository.saveLead(conversationId, known.merge(null, null,
                new ModelAnswer.LeadDetails(name, phone, email, null, null, null, null, null, null, null, null)));
    }

    private Map<String, Object> reply(List<String> replies) {
        var response = new LinkedHashMap<String, Object>();
        response.put("action", "reply");
        response.put("replies", replies);
        return response;
    }

    private static Map<String, Object> action(String action, String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("action", action);
        response.put("replies", List.of(message));
        return response;
    }

    private Map<String, Object> forward(String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("action", "forward");
        response.put("replies", List.of(message));
        if (department != null && !department.isBlank()) response.put("department", department);
        return response;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String rootCause(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getClass().getSimpleName();
    }
}
