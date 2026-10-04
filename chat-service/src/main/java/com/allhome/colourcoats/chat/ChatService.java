package com.allhome.colourcoats.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import com.allhome.colourcoats.flowlog.FlowLog;
import com.allhome.colourcoats.flowlog.TraceContext;
import com.allhome.colourcoats.retrieval.KnowledgeSearch;
import com.allhome.colourcoats.retrieval.RetrievedChunk;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class ChatService {
    static final String FALLBACK_REPLY = "Sorry, I couldn't answer that properly. A ColourCoats specialist will get in touch with you. "
            + "Could you share your name and phone number?";
    private final BeanOutputConverter<ModelAnswer> answerConverter = new BeanOutputConverter<>(ModelAnswer.class);
    private final ChatClient chatClient;
    private final KnowledgeSearch knowledgeSearch;
    private final ChatRepository repository;
    private final TransactionOperations transaction;
    private final SystemPrompts systemPrompts;
    private final int historySize;
    private final boolean liveTransfer;
    private final String transferMessage;
    @Value("${spring.ai.openai.chat.model:unknown}") private String requestedModel = "unknown";
    @Value("${spring.ai.openai.chat.temperature:0.4}") private double temperature = 0.4;
    @Value("${spring.ai.openai.chat.max-completion-tokens:600}") private int maxTokens = 600;

    public ChatService(ChatClient.Builder chatClientBuilder, KnowledgeSearch knowledgeSearch,
                       ChatRepository repository, TransactionOperations transaction, SystemPrompts systemPrompts,
                       @Value("${chat.history-size:20}") int historySize,
                       @Value("${salesiq.forward-on-handoff:true}") boolean liveTransfer,
                       @Value("${salesiq.transfer-message:Let me connect you with one of our specialists now.}") String transferMessage) {
        this.chatClient = chatClientBuilder.build();
        this.knowledgeSearch = knowledgeSearch;
        this.repository = repository;
        this.transaction = transaction;
        this.systemPrompts = systemPrompts;
        this.historySize = historySize;
        this.liveTransfer = liveTransfer;
        this.transferMessage = transferMessage;
    }

    public Reply chat(UUID conversationId, String userMessage, Channel channel) {
        try (var scope = TraceContext.open()) {
            var context = TraceContext.current();
            context.bind(conversationId, channel.name());
            context.completion.checkActive();
            return answer(conversationId, userMessage, channel);
        }
    }

    private Reply answer(UUID conversationId, String userMessage, Channel channel) {
        var context = TraceContext.current();
        repository.touchConversation(conversationId, channel);
        var history = repository.recentMessages(conversationId, historySize);
        Lead known = repository.findLead(conversationId);
        // Persist the visitor's message before calling the model so it survives a provider failure.
        // The SalesIQ request thread has already saved it before launching this worker.
        if (!context.completion.deferred()) repository.saveUserMessage(conversationId, userMessage);

        var documents = retrieve(retrievalQuery(history, userMessage));
        context.completion.checkActive();

        // Text is passed as-is (no template parameters), so braces in user input or knowledge are not interpreted.
        var historyMessages = toMessages(history);
        String userTurn = currentTurn(userMessage, documents, known, channel) + "\n\n" + answerConverter.getFormat();
        var systemPrompt = systemPrompts.active();
        logModelRequest(systemPrompt, historyMessages, userTurn);
        long modelStarted = System.nanoTime();
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt.content())
                    .messages(historyMessages)
                    .user(userTurn)
                    .call().chatResponse();
        } catch (Throwable e) { // any outcome, incl. cancellation at the SalesIQ deadline, must leave evidence
            FlowLog.log("openai.response", "error", e.toString(), "interrupted", Thread.currentThread().isInterrupted(),
                    "duration_ms", elapsedMs(modelStarted));
            throw e;
        }
        String output = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        logModelResponse(response, output, elapsedMs(modelStarted));

        context.completion.checkActive();
        var parsed = parse(output);
        ModelAnswer answer = parsed.answer();
        var sources = documents.stream().map(ChatService::toSource).toList();
        var metadata = response == null ? null : response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
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
        // The reply, the lead and the handoff request are saved together or not at all.
        Runnable commit = () -> {
            transaction.executeWithoutResult(status -> {
                repository.saveAssistantMessage(conversationId, reply, handoff, sources, metadata == null ? null : metadata.getModel(),
                        usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(),
                        systemPrompt.versionId());
                repository.saveLead(conversationId, mergedLead);
                if (handoff) repository.requestHandoff(conversationId, handoffReason);
            });
            TraceContext.outcome(outcome);
        };
        if (context.completion.deferred()) context.completion.prepare(commit); else commit.run();
        return new Reply(conversationId, reply, handoff, sources);
    }

    /** Flow log: the exact messages and settings sent to the model, in send order, and the prompt version used. */
    private void logModelRequest(SystemPrompts.SystemPrompt systemPrompt, List<Message> historyMessages, String userTurn) {
        if (!FlowLog.enabled()) return;
        var messages = new ArrayList<Map<String, String>>();
        messages.add(flowMessage("system", systemPrompt.content()));
        for (var message : historyMessages) {
            messages.add(flowMessage(message instanceof UserMessage ? "user" : "assistant", message.getText()));
        }
        messages.add(flowMessage("user", userTurn));
        FlowLog.log("openai.request", "model", requestedModel, "temperature", temperature,
                "max_completion_tokens", maxTokens,
                "prompt_version_id", systemPrompt.versionId() == null ? null : systemPrompt.versionId().toString(),
                "prompt_version", systemPrompt.versionNumber(), "messages", messages);
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
            // Not valid JSON for ModelAnswer: handled below like an empty answer.
        }
        return new ParsedAnswer(new ModelAnswer(FALLBACK_REPLY, true, "Assistant returned an unusable answer; review the transcript.",
                null, null, null), false);
    }
    private record ParsedAnswer(ModelAnswer answer, boolean valid) {}

    /** Hybrid retrieval (vector + keyword) over the active knowledge version; see KnowledgeSearch. */
    private List<RetrievedChunk> retrieve(String query) {
        return knowledgeSearch.search(query);
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
    private static String currentTurn(String userMessage, List<RetrievedChunk> documents, Lead known, Channel channel) {
        var prompt = new StringBuilder("<knowledge>\n");
        if (documents.isEmpty()) prompt.append("No relevant ColourCoats knowledge was found for this message.\n");
        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            prompt.append("[").append(i + 1).append("] source: ").append(doc.url())
                    .append("\n").append(doc.text()).append("\n\n");
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

    private static ChatSource toSource(RetrievedChunk chunk) {
        String section = chunk.headingPath().isEmpty() ? null : String.join(" > ", chunk.headingPath());
        String url = chunk.sourceUrls().isEmpty() ? chunk.url() : chunk.sourceUrls().get(0);
        return new ChatSource(chunk.id().toString(), url, section, chunk.similarity());
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public record Reply(UUID conversationId, String reply, boolean handoff, List<ChatSource> sources) {}
}
