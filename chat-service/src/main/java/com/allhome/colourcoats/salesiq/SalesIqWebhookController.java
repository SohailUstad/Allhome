package com.allhome.colourcoats.salesiq;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.ModelAnswer;
import com.allhome.colourcoats.flowlog.*;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Zoho SalesIQ Zobot webhook (request/response format 2.0). Register
 * {@code https://<public-host>/api/salesiq/webhook} as the bot's webhook URL.
 *
 * SalesIQ waits at most 5 seconds and the async "pending" action is not supported on Instagram, so every
 * reply is time-boxed: if the pipeline misses the deadline (or fails), the visitor gets a holding message,
 * the conversation is queued for a human, and SalesIQ always receives a valid 200 response.
 */
@RestController
@RequestMapping("/api/salesiq/webhook")
public class SalesIqWebhookController {
    // SalesIQ fills anonymous visitors with placeholder names such as "Visitor 51234".
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("(?i)^(visitor|guest)\\b.*");

    private final ChatService chatService;
    private final ChatRepository repository;
    private final SalesIqSignatureVerifier verifier;
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
    private Telemetry telemetry = Telemetry.local();
    private RunJournal journal = RunJournal.transientJournal();

    @Autowired public void observability(Telemetry telemetry, RunJournal journal) {
        this.telemetry = telemetry;
        this.journal = journal;
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }

    // Async ("pending") replies for slow turns; null or unconfigured client = always answer synchronously.
    private SalesIqCallbackClient callbacks;
    private long pendingAfterMs = 2500;
    private long asyncMaxWaitMs = 90000;
    private String pendingMessage = "";

    @Autowired public void async(SalesIqCallbackClient callbacks,
                                 @Value("${salesiq.async.pending-after-ms:2500}") long pendingAfterMs,
                                 @Value("${salesiq.async.max-wait-ms:90000}") long asyncMaxWaitMs,
                                 @Value("${salesiq.async.pending-message:}") String pendingMessage) {
        this.callbacks = callbacks;
        this.pendingAfterMs = pendingAfterMs;
        this.asyncMaxWaitMs = asyncMaxWaitMs;
        this.pendingMessage = pendingMessage;
    }

    public SalesIqWebhookController(ChatService chatService, ChatRepository repository, SalesIqSignatureVerifier verifier,
                                    JsonMapper json,
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
        this.verifier = verifier;
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
    public ResponseEntity<Map<String, Object>> handle(@RequestBody(required = false) byte[] rawBody,
                                                      @RequestHeader(value = "x-siqsignature", required = false) String signature) {
        try (var scope = TraceContext.open()) {
            long started = System.nanoTime();
            String body = rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);
            logReceived(body);
            try {
                var response = respond(body, signature);
                FlowLog.log("salesiq.response", "http_status", response.getStatusCode().value(),
                        "payload", response.getBody(), "duration_ms", Telemetry.elapsed(started));
                return response;
            } catch (RuntimeException e) {
                FlowLog.log("salesiq.response", "error", e.toString(), "duration_ms", Telemetry.elapsed(started));
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

    private ResponseEntity<Map<String, Object>> respond(String body, String signature) {
        if (verifier.enabled() && !verifier.verify(body, signature)) {
            telemetry.warning("salesiq.signature.rejected");
            TraceContext.outcome("rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                    .body(reply(List.of("Unauthorized")));
        }
        JsonNode request;
        try {
            request = body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (RuntimeException e) {
            telemetry.warning("salesiq.payload.invalid");
            TraceContext.outcome("invalid_payload");
            return ok(reply(List.of(opener)));
        }
        String handler = text(request.path("handler"));
        if (!"message".equals(handler)) {
            telemetry.event("salesiq.greeting.selected");
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
            telemetry.event("salesiq.message.unsupported");
            return reply(List.of("I can only read text messages at the moment. Could you type your question?"));
        }
        if (message.length() > 2000) {
            telemetry.event("salesiq.message.truncated", "original_length", message.length(), "retained_length", 2000);
            message = message.substring(0, 2000);
        }

        UUID conversationId = conversationId(visitor);
        Channel channel = "instagram".equalsIgnoreCase(text(visitor.path("channel"))) ? Channel.INSTAGRAM : Channel.ZOHO_SALESIQ;
        var context = TraceContext.current();
        context.bind(conversationId, channel.name());
        // This is a delivery reference, not a guaranteed logical message ID: correlate redeliveries without suppressing turns.
        String external = text(request.path("request").path("id"));
        if (external != null) context.externalEventHash = Telemetry.hash(conversationId + ":" + external);
        journal.begin();
        repository.touchConversation(conversationId, channel);
        seedLeadFromVisitor(conversationId, visitor);
        repository.saveUserMessage(conversationId, message);

        // Once a chat is handed to a human, the AI never answers it again. SalesIQ only calls the bot again if no
        // operator took the chat, so let SalesIQ's own "operators busy / leave a message" flow handle it.
        if (forwardOnHandoff && repository.isForwarded(conversationId)) {
            journal.finish("operator_busy", () -> {
                repository.saveAssistantMessage(conversationId, busyMessage, true, List.of(), null, null, null);
                repository.requestHandoff(conversationId, "Visitor wrote again after the transfer; no operator picked up the chat.");
            });
            TraceContext.outcome("operator_busy");
            telemetry.event("salesiq.operator_busy");
            return action("operator_busy", busyMessage);
        }

        context.completion.defer();
        if (Telemetry.elapsed(started) >= deadlineMs) {
            context.completion.abandon();
            telemetry.warning("salesiq.deadline.exceeded", "deadline_ms", deadlineMs, "worker_started", false);
            return fallback(conversationId, "Bot reply exceeded the SalesIQ time limit", "fallback_timeout");
        }
        String finalMessage = message;
        var future = executor.submit(telemetry.wrap(() -> {
            try {
                return chatService.chat(conversationId, finalMessage, channel);
            } finally {
                telemetry.event("salesiq.worker.finished", "abandoned", context.completion.abandoned(),
                        "interrupted", Thread.currentThread().isInterrupted());
                if (context.completion.abandoned()) telemetry.warning("salesiq.worker.late_completion");
            }
        }));
        // Website chats can be answered later through SalesIQ's callback API (not supported on Instagram).
        boolean async = callbacks != null && callbacks.enabled() && channel == Channel.ZOHO_SALESIQ && external != null;
        long waitMs = async ? Math.min(pendingAfterMs, deadlineMs) : deadlineMs;
        try {
            long remaining = waitMs - Telemetry.elapsed(started);
            if (remaining <= 0) throw new TimeoutException();
            var result = future.get(remaining, TimeUnit.MILLISECONDS);
            journal.atomic(() -> {
                context.completion.accept();
                if (result.handoff() && forwardOnHandoff) repository.markForwarded(conversationId);
            });
            telemetry.event("salesiq.reply.prepared", "duration_ms", Telemetry.elapsed(started), "handoff", result.handoff());
            if (result.handoff() && forwardOnHandoff) {
                return forward(result.reply());
            }
            return reply(List.of(result.reply()));
        } catch (TimeoutException e) {
            if (async) return pending(future, conversationId, external, started);
            context.completion.abandon();
            boolean cancellationRequested = future.cancel(true);
            telemetry.warning("salesiq.deadline.exceeded", "deadline_ms", deadlineMs, "duration_ms", Telemetry.elapsed(started),
                    "cancellation_requested", cancellationRequested);
            return fallback(conversationId, "Bot reply exceeded the SalesIQ time limit", "fallback_timeout");
        } catch (Exception e) {
            context.completion.abandon();
            future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            telemetry.failure("salesiq.reply.failed", e);
            return fallback(conversationId, "Bot reply failed (" + rootCause(e) + ")", "fallback_error");
        }
    }

    /**
     * Slow turn on the website: tell SalesIQ the reply is "pending" now, keep the worker running, and deliver its
     * answer (or the usual fallback) through the callback API once it is ready.
     */
    private Map<String, Object> pending(Future<ChatService.Reply> future, UUID conversationId, String requestId, long started) {
        executor.submit(telemetry.wrap(() -> {
            completeLater(future, conversationId, requestId, started);
            return null;
        }));
        TraceContext.outcome("pending");
        var response = new LinkedHashMap<String, Object>();
        response.put("action", "pending");
        response.put("replies", pendingMessage == null || pendingMessage.isBlank() ? List.of() : List.of(pendingMessage));
        return response;
    }

    private void completeLater(Future<ChatService.Reply> future, UUID conversationId, String requestId, long started) {
        var context = TraceContext.current();
        Map<String, Object> body;
        try {
            var result = future.get(Math.max(asyncMaxWaitMs - Telemetry.elapsed(started), 0), TimeUnit.MILLISECONDS);
            journal.atomic(() -> {
                context.completion.accept();
                if (result.handoff() && forwardOnHandoff) repository.markForwarded(conversationId);
            });
            body = result.handoff() && forwardOnHandoff ? forward(result.reply()) : reply(List.of(result.reply()));
        } catch (TimeoutException e) {
            context.completion.abandon();
            future.cancel(true);
            telemetry.warning("salesiq.async.deadline.exceeded", "max_wait_ms", asyncMaxWaitMs);
            body = fallback(conversationId, "Bot reply exceeded the async time limit", "fallback_timeout");
        } catch (Exception e) {
            context.completion.abandon();
            future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            telemetry.failure("salesiq.async.reply.failed", e);
            body = fallback(conversationId, "Bot reply failed (" + rootCause(e) + ")", "fallback_error");
        }
        var sent = callbacks.respond(requestId, body);
        FlowLog.log("salesiq.callback", "salesiq_request_id", requestId, "payload", body,
                "http_status", sent.status(), "callback_response", sent.body(), "duration_ms", Telemetry.elapsed(started));
        if (!sent.ok()) telemetry.warning("salesiq.callback.failed", "http_status", sent.status());
    }

    /** The visitor is never left without an answer: a human follows up on what the bot could not answer in time. */
    private Map<String, Object> fallback(UUID conversationId, String reason, String outcome) {
        // Forwarding: an operator takes over live, so no contact request; otherwise ask for a number to call back.
        String message = forwardOnHandoff ? transferMessage : fallbackMessage;
        try {
            if (!forwardOnHandoff && repository.findLead(conversationId).phone() == null) message = fallbackMessage + " " + contactRequest;
            String selected = message;
            journal.finish(outcome, () -> {
                repository.saveAssistantMessage(conversationId, selected, true, List.of(), null, null, null);
                repository.requestHandoff(conversationId, reason + "; follow up on the visitor's last message.");
                if (forwardOnHandoff) repository.markForwarded(conversationId);
            });
            telemetry.event("salesiq.fallback.committed", "outcome", outcome);
        } catch (RuntimeException e) {
            telemetry.failure("salesiq.fallback.persist_failed", e);
            outcome = "fallback_persistence_failed";
        }
        TraceContext.outcome(outcome);
        telemetry.count("app.chat.turns", outcome);
        telemetry.event("salesiq.response.prepared", "outcome", outcome, "action", forwardOnHandoff ? "forward" : "reply");
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

    private static String rootCause(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getClass().getSimpleName();
    }
}
