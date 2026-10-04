package com.allhome.colourcoats.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import com.allhome.colourcoats.flowlog.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    static final String FALLBACK_REPLY = "Sorry, I couldn't answer that properly. A ColourCoats specialist will get in touch with you. "
            + "Could you share your name and phone number?";
    private final BeanOutputConverter<ModelAnswer> answerConverter = new BeanOutputConverter<>(ModelAnswer.class);
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final KeywordRetriever keywordRetriever;
    private final ChatRepository repository;
    private final String systemPrompt;
    private final int historySize;
    private final int topK;
    private final double similarityThreshold;
    private final int keywordTopK;
    private final boolean liveTransfer;
    private final String transferMessage;
    private Telemetry telemetry = Telemetry.local();
    private RunJournal journal = RunJournal.transientJournal();
    @Value("${spring.ai.openai.chat.model:unknown}") private String requestedModel = "unknown";
    @Value("${spring.ai.openai.chat.temperature:0.4}") private double temperature = 0.4;
    @Value("${spring.ai.openai.chat.max-completion-tokens:600}") private int maxTokens = 600;

    @Autowired
    public void observability(Telemetry telemetry, RunJournal journal) {
        this.telemetry = telemetry;
        this.journal = journal;
    }

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, KeywordRetriever keywordRetriever,
                       ChatRepository repository,
                       @Value("classpath:prompts/sales-system.md") Resource systemPrompt,
                       @Value("${chat.history-size:20}") int historySize,
                       @Value("${chat.retrieval.top-k:6}") int topK,
                       @Value("${chat.retrieval.similarity-threshold:0.3}") double similarityThreshold,
                       @Value("${chat.retrieval.keyword-top-k:3}") int keywordTopK,
                       @Value("${salesiq.forward-on-handoff:true}") boolean liveTransfer,
                       @Value("${salesiq.transfer-message:Let me connect you with one of our specialists now.}") String transferMessage) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;
        this.repository = repository;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.historySize = historySize;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.keywordTopK = keywordTopK;
        this.liveTransfer = liveTransfer;
        this.transferMessage = transferMessage;
    }

    public Reply chat(UUID conversationId, String userMessage, Channel channel) {
        try (var scope = TraceContext.open()) {
            var context = TraceContext.current();
            context.bind(conversationId, channel.name());
            context.completion.checkActive();
            journal.begin();
            long started = System.nanoTime();
            telemetry.event("chat.turn.started");
            try {
                return telemetry.stage("chat.pipeline", () -> answer(conversationId, userMessage, channel));
            } catch (RuntimeException error) {
                // SalesIQ owns the fallback outcome; a direct chat failure is finalized here.
                if (!context.completion.deferred()) {
                    TraceContext.outcome("failed");
                    boolean recorded = false;
                    try { journal.finish("failed", () -> {}); recorded = true; }
                    catch (RuntimeException persistenceError) { telemetry.failure("chat.turn.record_failed", persistenceError); }
                    telemetry.event("chat.turn.failed", "outcome", "failed", "journal_recorded", recorded);
                    telemetry.count("app.chat.turns", "failed");
                }
                throw error;
            } finally {
                telemetry.event("chat.worker.finished", "duration_ms", Telemetry.elapsed(started),
                        "abandoned", context.completion.abandoned());
            }
        }
    }

    private Reply answer(UUID conversationId, String userMessage, Channel channel) {
        var context = TraceContext.current();
        repository.touchConversation(conversationId, channel);
        var history = telemetry.stage("chat.history", () -> repository.recentMessages(conversationId, historySize));
        Lead known = telemetry.stage("chat.lead.load", () -> repository.findLead(conversationId));
        // Persist the visitor's message before calling the model so it survives a provider failure.
        // The SalesIQ request thread has already saved it before launching this worker.
        if (!context.completion.deferred()) telemetry.stage("chat.user.persist", () -> repository.saveUserMessage(conversationId, userMessage));

        var documents = retrieve(retrievalQuery(history, userMessage));
        context.completion.checkActive();
        String promptHash = Telemetry.hash(systemPrompt);
        String schemaHash = Telemetry.hash(answerConverter.getFormat());
        journal.artifact("system_prompt", promptHash, systemPrompt);
        journal.artifact("output_schema", schemaHash, answerConverter.getFormat());
        var selected = documents.stream().map(doc -> {
            String hash = Telemetry.hash(doc.getText());
            journal.artifact("knowledge", hash, doc.getText());
            return Map.of("row_id", Telemetry.reference(doc.getId()), "chunk_id", Telemetry.reference(dedupeKey(doc)),
                    "dataset_version", Telemetry.reference(doc.getMetadata().get("dataset_version")), "content_hash", hash);
        }).toList();
        var execution = new LinkedHashMap<String, Object>();
        execution.put("prompt_hash", promptHash);
        execution.put("schema_hash", schemaHash);
        execution.put("policy_version", HandoffPolicy.VERSION);
        execution.put("history_message_ids", history.stream().map(ChatRepository.StoredMessage::id).filter(java.util.Objects::nonNull).toList());
        execution.put("history_count", history.size());
        execution.put("lead_snapshot", known); // Protected business DB only; never emitted to operational logs.
        execution.put("selected_knowledge", selected);
        execution.put("requested_model", requestedModel);
        execution.put("temperature", temperature);
        execution.put("max_completion_tokens", maxTokens);
        execution.put("retrieval", Map.of("top_k", topK, "keyword_top_k", keywordTopK, "threshold", similarityThreshold));
        journal.snapshot(execution);
        telemetry.event("chat.prompt.prepared", "prompt_hash", promptHash, "schema_hash", schemaHash,
                "policy_version", HandoffPolicy.VERSION, "history_count", history.size(), "knowledge_count", documents.size());

        // Text is passed as-is (no template parameters), so braces in user input or knowledge are not interpreted.
        var historyMessages = toMessages(history);
        String userTurn = currentTurn(userMessage, documents, known, channel) + "\n\n" + answerConverter.getFormat();
        logModelRequest(historyMessages, userTurn);
        long modelStarted = System.nanoTime();
        ChatResponse response;
        try {
            response = telemetry.stage("chat.model", () -> chatClient.prompt()
                    .system(systemPrompt)
                    .messages(historyMessages)
                    .user(userTurn)
                    .call().chatResponse());
        } catch (Throwable e) { // any outcome, incl. cancellation at the SalesIQ deadline, must leave evidence
            FlowLog.log("openai.response", "error", e.toString(), "interrupted", Thread.currentThread().isInterrupted(),
                    "duration_ms", Telemetry.elapsed(modelStarted));
            throw e;
        }
        String output = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        logModelResponse(response, output, Telemetry.elapsed(modelStarted));

        context.completion.checkActive();
        var parsed = telemetry.stage("chat.output.validate", () -> parse(output));
        if (!parsed.valid()) journal.invalidOutput(output);
        ModelAnswer answer = parsed.answer();
        if (answer.persona() != null && !Lead.PERSONAS.contains(answer.persona().strip().toUpperCase())) {
            telemetry.warning("chat.output.invalid_persona");
        }
        var sources = documents.stream().map(ChatService::toSource).toList();
        var metadata = response == null ? null : response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        telemetry.event("chat.model.usage", "model", metadata == null ? "unknown" : Telemetry.reference(metadata.getModel()),
                "provider_request_id", metadata == null ? "unknown" : Telemetry.reference(metadata.getId()),
                "prompt_tokens", usage == null ? null : usage.getPromptTokens(),
                "completion_tokens", usage == null ? null : usage.getCompletionTokens(),
                "finish_reason", response == null || response.getResult() == null ? "unknown"
                        : Telemetry.reference(response.getResult().getMetadata().getFinishReason()));
        // Routing must not depend on the model remembering to set its flag: rules can only add a handoff.
        var ruleReasons = HandoffPolicy.ruleReasons(userMessage);
        boolean handoff = answer.handoff() || !ruleReasons.isEmpty();
        String handoffReason = answer.handoffReason() != null && !answer.handoffReason().isBlank()
                ? answer.handoffReason() : "Rule: " + String.join(", ", ruleReasons);
        // On SalesIQ the chat is transferred right after this reply, so it must not ask anything (enforced, not hoped for).
        String reply = handoff && liveTransfer && channel.supportsLiveTransfer()
                ? HandoffPolicy.transferReply(answer.reply(), transferMessage) : answer.reply();
        var mergedLead = known.merge(answer.persona(), answer.intent(), answer.lead());
        String outcome = !parsed.valid() ? "fallback_invalid_output" : handoff ? "handoff_requested" : "answered";
        telemetry.event("chat.handoff.decided", "model_handoff", answer.handoff(), "rule_reasons", ruleReasons,
                "final_handoff", handoff, "reply_transformed", !reply.equals(answer.reply()), "outcome", outcome);
        Runnable commit = () -> {
            telemetry.stage("chat.result.persist", () -> journal.finish(outcome, () -> {
                repository.saveAssistantMessage(conversationId, reply, handoff, sources, metadata == null ? null : metadata.getModel(),
                        usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
                repository.saveLead(conversationId, mergedLead);
                if (handoff) repository.requestHandoff(conversationId, handoffReason);
            }));
            TraceContext.outcome(outcome);
            journal.afterCommit(() -> {
                telemetry.event("chat.turn.completed", "outcome", outcome);
                telemetry.count("app.chat.turns", outcome);
            });
        };
        if (context.completion.deferred()) context.completion.prepare(commit); else commit.run();
        return new Reply(conversationId, reply, handoff, sources);
    }

    /** Flow log: the exact messages and settings sent to the model, in send order. */
    private void logModelRequest(List<Message> historyMessages, String userTurn) {
        if (!FlowLog.enabled()) return;
        var messages = new ArrayList<Map<String, String>>();
        messages.add(flowMessage("system", systemPrompt));
        for (var message : historyMessages) {
            messages.add(flowMessage(message instanceof UserMessage ? "user" : "assistant", message.getText()));
        }
        messages.add(flowMessage("user", userTurn));
        FlowLog.log("openai.request", "model", requestedModel, "temperature", temperature,
                "max_completion_tokens", maxTokens, "messages", messages);
    }

    private static Map<String, String> flowMessage(String role, String content) {
        var message = new LinkedHashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /** Flow log: the model's raw output (before parsing or handoff rules) and how long the call took. */
    private void logModelResponse(ChatResponse response, String output, long durationMs) {
        if (!FlowLog.enabled()) return;
        var metadata = response == null ? null : response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        FlowLog.log("openai.response", "duration_ms", durationMs,
                "model", metadata == null ? null : metadata.getModel(),
                "openai_response_id", metadata == null ? null : metadata.getId(),
                "prompt_tokens", usage == null ? null : usage.getPromptTokens(),
                "completion_tokens", usage == null ? null : usage.getCompletionTokens(),
                "finish_reason", response == null || response.getResult() == null ? null : response.getResult().getMetadata().getFinishReason(),
                "content", output);
    }

    /** A reply we cannot parse is never shown raw; the visitor is routed to a human instead. */
    private ParsedAnswer parse(String text) {
        try {
            ModelAnswer answer = text == null ? null : answerConverter.convert(text);
            if (answer != null && answer.reply() != null && !answer.reply().isBlank()) return new ParsedAnswer(answer, true);
        } catch (RuntimeException e) {
            telemetry.warning("chat.output.parse_failed", "error_type", e.getClass().getName());
        }
        telemetry.warning("chat.output.invalid");
        return new ParsedAnswer(new ModelAnswer(FALLBACK_REPLY, true, "Assistant returned an unusable answer; review the transcript.",
                null, null, null), false);
    }
    private record ParsedAnswer(ModelAnswer answer, boolean valid) {}

    /** Hybrid retrieval: semantic matches plus exact rare-word matches (product and place names), de-duplicated. */
    private List<Document> retrieve(String query) {
        var merged = new java.util.LinkedHashMap<String, Document>();
        var semantic = telemetry.stage("retrieval.vector", () -> vectorStore.similaritySearch(SearchRequest.builder()
                .query(query).topK(topK).similarityThreshold(similarityThreshold).build()));
        var keyword = keywordTopK > 0 ? telemetry.stage("retrieval.keyword", () -> keywordRetriever.search(query, keywordTopK)) : List.<Document>of();
        for (var doc : semantic) merged.putIfAbsent(dedupeKey(doc), doc);
        for (var doc : keyword) merged.putIfAbsent(dedupeKey(doc), doc);
        telemetry.event("retrieval.completed", "vector_count", semantic.size(), "keyword_count", keyword.size(),
                "selected_count", merged.size(), "deduplicated_count", semantic.size() + keyword.size() - merged.size(),
                "top_k", topK, "keyword_top_k", keywordTopK, "similarity_threshold", similarityThreshold);
        int rank = 0;
        for (var doc : merged.values()) telemetry.event("retrieval.selected", "rank", ++rank,
                "row_id", Telemetry.reference(doc.getId()), "chunk_id", Telemetry.reference(dedupeKey(doc)),
                "dataset_version", Telemetry.reference(doc.getMetadata().get("dataset_version")), "score", doc.getScore(),
                "retriever", semantic.contains(doc) ? "vector" : "keyword");
        return List.copyOf(merged.values());
    }

    // The same chunk can exist as several rows (re-ingested versions); one copy is enough for the model.
    private static String dedupeKey(Document doc) {
        Object chunkId = doc.getMetadata().get("chunk_id");
        return chunkId != null ? chunkId.toString() : doc.getId();
    }

    /** Follow-ups like "how long does it take?" only make sense with the previous visitor question. */
    private static String retrievalQuery(List<ChatRepository.StoredMessage> history, String userMessage) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("USER".equals(history.get(i).role())) return history.get(i).content() + "\n" + userMessage;
        }
        return userMessage;
    }

    private static List<Message> toMessages(List<ChatRepository.StoredMessage> history) {
        List<Message> messages = new ArrayList<>();
        for (var message : history) {
            messages.add("USER".equals(message.role())
                    ? new UserMessage(message.content())
                    : new AssistantMessage(message.content()));
        }
        return messages;
    }

    // Knowledge and the lead profile are attached only to the current turn; history stores the visitor's raw words.
    private static String currentTurn(String userMessage, List<Document> documents, Lead known, Channel channel) {
        var prompt = new StringBuilder("<knowledge>\n");
        if (documents.isEmpty()) prompt.append("No relevant ColourCoats knowledge was found for this message.\n");
        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            prompt.append("[").append(i + 1).append("] source: ").append(doc.getMetadata().getOrDefault("url", "unknown"))
                    .append("\n").append(doc.getText()).append("\n\n");
        }
        prompt.append("</knowledge>\n\n<conversation_context>\nchannel: ").append(channel)
                .append("\n</conversation_context>\n\n<lead_profile>\n");
        appendField(prompt, "persona", known.persona());
        appendField(prompt, "intent", known.intent());
        appendField(prompt, "status", known.status());
        appendField(prompt, "name", known.name());
        appendField(prompt, "phone", known.phone());
        appendField(prompt, "email", known.email());
        appendField(prompt, "city", known.city());
        appendField(prompt, "projectType", known.projectType());
        appendField(prompt, "spaces", known.spaces());
        appendField(prompt, "finishInterest", known.finishInterest());
        appendField(prompt, "areaSize", known.areaSize());
        appendField(prompt, "timeline", known.timeline());
        appendField(prompt, "budget", known.budget());
        appendField(prompt, "callbackTime", known.callbackTime());
        return prompt.append("</lead_profile>\n\nVisitor message:\n").append(userMessage).toString();
    }

    private static void appendField(StringBuilder prompt, String name, String value) {
        prompt.append(name).append(": ").append(value == null ? "unknown" : value).append("\n");
    }

    private static ChatSource toSource(Document doc) {
        var meta = doc.getMetadata();
        Object path = meta.get("heading_path");
        String section = path instanceof List<?> list
                ? String.join(" > ", list.stream().map(String::valueOf).toList())
                : path == null ? null : path.toString();
        Object sourceUrls = meta.get("source_urls");
        Object url = sourceUrls instanceof List<?> urls && !urls.isEmpty() ? urls.get(0) : meta.get("url");
        return new ChatSource(String.valueOf(meta.getOrDefault("chunk_id", doc.getId())),
                url == null ? null : url.toString(), section, doc.getScore());
    }

    public record Reply(UUID conversationId, String reply, boolean handoff, List<ChatSource> sources) {}
}
