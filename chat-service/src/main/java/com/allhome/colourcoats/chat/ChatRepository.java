package com.allhome.colourcoats.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import com.allhome.colourcoats.flowlog.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ChatRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private RunJournal journal = RunJournal.transientJournal();

    @Autowired public void observability(RunJournal journal) { this.journal = journal; }

    public ChatRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Creates the conversation if it does not exist and marks it active; safe to call on every turn. */
    public void touchConversation(UUID id, Channel channel) {
        jdbc.sql("""
                INSERT INTO chat_conversation (id, channel) VALUES (:id, :channel)
                ON CONFLICT (id) DO UPDATE SET updated_at = now()
                """).param("id", id).param("channel", channel.name()).update();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    @Transactional
    public void saveUserMessage(UUID conversationId, String content) {
        jdbc.sql("INSERT INTO chat_message (conversation_id, role, content, turn_id, request_id, trace_id) VALUES (:c, 'USER', :content, :turn, :request, :trace)")
                .param("c", conversationId).param("content", content)
                .param("turn", turnId()).param("request", requestId()).param("trace", MDC.get("traceId")).update();
        journal.audit("chat.user_message.saved", Map.of());
    }

    @Transactional
    public void saveAssistantMessage(UUID conversationId, String content, boolean handoff, List<ChatSource> sources,
                                     String model, Integer promptTokens, Integer completionTokens) {
        jdbc.sql("""
                INSERT INTO chat_message (conversation_id, role, content, handoff, sources, model, prompt_tokens, completion_tokens, turn_id, request_id, trace_id)
                VALUES (:c, 'ASSISTANT', :content, :handoff, CAST(:sources AS JSONB), :model, :pt, :ct, :turn, :request, :trace)
                """)
                .param("c", conversationId).param("content", content).param("handoff", handoff)
                .param("sources", json.writeValueAsString(sources))
                .param("model", model).param("pt", promptTokens).param("ct", completionTokens)
                .param("turn", turnId()).param("request", requestId()).param("trace", MDC.get("traceId"))
                .update();
        journal.audit("chat.assistant_message.saved", Map.of("handoff", handoff));
    }

    /** Queues the conversation for a human; keeps the first request time, records the latest reason. */
    @Transactional
    public void requestHandoff(UUID conversationId, String reason) {
        jdbc.sql("""
                UPDATE chat_conversation
                SET handoff_requested_at = COALESCE(handoff_requested_at, now()), handoff_reason = :reason
                WHERE id = :id
                """).param("id", conversationId).param("reason", reason).update();
        journal.audit("handoff.requested", Map.of());
    }

    /** True once the conversation was transferred to a SalesIQ operator. */
    public boolean isForwarded(UUID conversationId) {
        return jdbc.sql("SELECT forwarded_at IS NOT NULL FROM chat_conversation WHERE id = :id")
                .param("id", conversationId).query(Boolean.class).optional().orElse(false);
    }

    @Transactional
    public void markForwarded(UUID conversationId) {
        jdbc.sql("UPDATE chat_conversation SET forwarded_at = COALESCE(forwarded_at, now()) WHERE id = :id")
                .param("id", conversationId).update();
        journal.audit("handoff.forward_prepared", Map.of());
    }

    public Lead findLead(UUID conversationId) {
        return jdbc.sql("""
                SELECT persona, intent, name, phone, email, city, project_type, spaces, finish_interest,
                       area_size, timeline, budget, callback_time
                FROM chat_lead WHERE conversation_id = :c
                """).param("c", conversationId)
                .query((rs, i) -> new Lead(rs.getString("persona"), rs.getString("intent"), rs.getString("name"),
                        rs.getString("phone"), rs.getString("email"), rs.getString("city"), rs.getString("project_type"),
                        rs.getString("spaces"), rs.getString("finish_interest"), rs.getString("area_size"),
                        rs.getString("timeline"), rs.getString("budget"), rs.getString("callback_time")))
                .optional().orElse(Lead.EMPTY);
    }

    @Transactional
    public void saveLead(UUID conversationId, Lead lead) {
        Lead previous = findLead(conversationId);
        jdbc.sql("""
                INSERT INTO chat_lead (conversation_id, status, persona, intent, name, phone, email, city, project_type,
                                       spaces, finish_interest, area_size, timeline, budget, callback_time)
                VALUES (:c, :status, :persona, :intent, :name, :phone, :email, :city, :projectType,
                        :spaces, :finishInterest, :areaSize, :timeline, :budget, :callbackTime)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    status = EXCLUDED.status, persona = EXCLUDED.persona, intent = EXCLUDED.intent,
                    name = EXCLUDED.name, phone = EXCLUDED.phone, email = EXCLUDED.email, city = EXCLUDED.city,
                    project_type = EXCLUDED.project_type, spaces = EXCLUDED.spaces,
                    finish_interest = EXCLUDED.finish_interest, area_size = EXCLUDED.area_size,
                    timeline = EXCLUDED.timeline, budget = EXCLUDED.budget, callback_time = EXCLUDED.callback_time,
                    updated_at = now()
                """)
                .param("c", conversationId).param("status", lead.status())
                .param("persona", lead.persona()).param("intent", lead.intent())
                .param("name", lead.name()).param("phone", lead.phone()).param("email", lead.email())
                .param("city", lead.city()).param("projectType", lead.projectType()).param("spaces", lead.spaces())
                .param("finishInterest", lead.finishInterest()).param("areaSize", lead.areaSize())
                .param("timeline", lead.timeline()).param("budget", lead.budget())
                .param("callbackTime", lead.callbackTime())
                .update();
        var changed = new java.util.ArrayList<String>();
        for (var field : Lead.class.getRecordComponents()) {
            try {
                if (!java.util.Objects.equals(field.getAccessor().invoke(previous), field.getAccessor().invoke(lead))) changed.add(field.getName());
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        journal.audit("lead.updated", Map.of("changed_fields", changed, "status", lead.status(), "conversation_id", conversationId));
    }

    /** The most recent {@code limit} messages, oldest first. */
    public List<StoredMessage> recentMessages(UUID conversationId, int limit) {
        return jdbc.sql("""
                SELECT id, role, content, created_at FROM (
                    SELECT id, role, content, created_at FROM chat_message
                    WHERE conversation_id = :c ORDER BY id DESC LIMIT :limit
                ) recent ORDER BY id
                """)
                .param("c", conversationId).param("limit", limit)
                .query((rs, i) -> new StoredMessage(rs.getString("role"), rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id")))
                .list();
    }

    private static UUID turnId() { return TraceContext.current() == null ? null : TraceContext.current().turnId; }
    private static String requestId() { return TraceContext.current() == null ? null : TraceContext.current().requestId; }
    public record StoredMessage(String role, String content, OffsetDateTime createdAt, Long id) {
        public StoredMessage(String role, String content, OffsetDateTime createdAt) { this(role, content, createdAt, null); }
    }
}
