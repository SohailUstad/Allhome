package com.allhome.colourcoats.operator;

import com.allhome.colourcoats.chat.ChatSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read model for the operator console: one row per conversation, joined with its lead profile. */
@Repository
public class LeadRepository {
    // Whitelisted ORDER BY clauses; user input never reaches SQL text.
    private static final Map<String, String> SORTS = Map.of(
            "updated", "c.updated_at DESC",
            "created", "c.created_at DESC",
            "handoff", "c.handoff_requested_at DESC NULLS LAST, c.updated_at DESC");

    private static final String FROM_WHERE = """
            FROM chat_conversation c LEFT JOIN chat_lead l ON l.conversation_id = c.id
            WHERE (CAST(:status AS text) IS NULL OR COALESCE(l.status, 'NEW') = :status)
              AND (CAST(:persona AS text) IS NULL OR COALESCE(l.persona, 'UNKNOWN') = :persona)
              AND (CAST(:intent AS text) IS NULL OR COALESCE(l.intent, 'UNKNOWN') = :intent)
              AND (CAST(:channel AS text) IS NULL OR c.channel = :channel)
              AND (CAST(:followUp AS text) IS NULL
                   OR (:followUp = 'needed' AND c.handoff_requested_at IS NOT NULL AND c.handled_at IS NULL)
                   OR (:followUp = 'handled' AND c.handled_at IS NOT NULL)
                   OR (:followUp = 'none' AND c.handoff_requested_at IS NULL))
              AND (CAST(:from AS date) IS NULL OR c.updated_at >= CAST(:from AS date))
              AND (CAST(:to AS date) IS NULL OR c.updated_at < CAST(:to AS date) + 1)
              AND (CAST(:q AS text) IS NULL OR concat_ws(' ', l.name, l.phone, l.email, l.city, l.project_type,
                   l.spaces, l.finish_interest, c.handoff_reason) ILIKE '%' || :q || '%')
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ZoneId zone;

    public LeadRepository(JdbcClient jdbc, JsonMapper json, @Value("${operator.time-zone:Asia/Kolkata}") ZoneId zone) {
        this.jdbc = jdbc;
        this.json = json;
        this.zone = zone;
    }

    public record LeadRow(UUID conversationId, String channel, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime handoffRequestedAt, String handoffReason, OffsetDateTime handledAt,
                          String status, String persona, String intent, String name, String phone, String email,
                          String city, String projectType, String spaces, String finishInterest, String areaSize,
                          String timeline, String budget, String callbackTime, int messageCount, String lastVisitorMessage,
                          OffsetDateTime forwardedAt) {
        public boolean needsFollowUp() { return handoffRequestedAt != null && handledAt == null; }

        /** One line for the sales rep: what they want, where, what kind of project. Null if nothing known yet. */
        public String requirement() {
            var parts = java.util.stream.Stream.of(finishInterest, spaces, projectType)
                    .filter(p -> p != null && !p.isBlank()).toList();
            return parts.isEmpty() ? null : String.join(" · ", parts);
        }

        /** Minimum fields for a qualified lead that are still unknown. */
        public List<String> missingMinimum() {
            var missing = new java.util.ArrayList<String>();
            if (name == null) missing.add("Name");
            if (phone == null) missing.add("Contact number");
            if (city == null) missing.add("City");
            if (persona == null || "UNKNOWN".equals(persona)) missing.add("Persona");
            if (requirement() == null) missing.add("Requirement");
            return missing;
        }
    }

    public record Message(String role, String content, boolean handoff, List<ChatSource> sources, OffsetDateTime createdAt) {}

    public record Summary(long total, long qualified, long needsFollowUp, long last7Days) {}

    public record Page(List<LeadRow> rows, long total, int page, int size) {
        public int pages() { return (int) Math.max(1, (total + size - 1) / size); }
    }

    public Page search(LeadFilter filter, int size) {
        long total = bind(jdbc.sql("SELECT count(*) " + FROM_WHERE), filter).query(Long.class).single();
        var rows = bind(jdbc.sql(select() + FROM_WHERE + " ORDER BY " + SORTS.getOrDefault(filter.sort(), SORTS.get("updated"))
                + " LIMIT :limit OFFSET :offset"), filter)
                .param("limit", size).param("offset", (long) (filter.page() - 1) * size)
                .query(this::row).list();
        return new Page(rows, total, filter.page(), size);
    }

    public List<LeadRow> export(LeadFilter filter, int max) {
        return bind(jdbc.sql(select() + FROM_WHERE + " ORDER BY " + SORTS.getOrDefault(filter.sort(), SORTS.get("updated"))
                + " LIMIT :limit"), filter).param("limit", max).query(this::row).list();
    }

    public Optional<LeadRow> find(UUID id) {
        return jdbc.sql(select() + "FROM chat_conversation c LEFT JOIN chat_lead l ON l.conversation_id = c.id WHERE c.id = :id")
                .param("id", id).query(this::row).optional();
    }

    public List<Message> messages(UUID id) {
        return jdbc.sql("SELECT role, content, handoff, sources::text AS sources, created_at FROM chat_message WHERE conversation_id = :id ORDER BY id")
                .param("id", id)
                .query((rs, i) -> new Message(rs.getString("role"), rs.getString("content"), rs.getBoolean("handoff"),
                        sources(rs.getString("sources")), ts(rs, "created_at")))
                .list();
    }

    public Summary summary() {
        return jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE l.status = 'QUALIFIED') AS qualified,
                       count(*) FILTER (WHERE c.handoff_requested_at IS NOT NULL AND c.handled_at IS NULL) AS follow_up,
                       count(*) FILTER (WHERE c.created_at >= now() - interval '7 days') AS recent
                FROM chat_conversation c LEFT JOIN chat_lead l ON l.conversation_id = c.id
                """).query((rs, i) -> new Summary(rs.getLong("total"), rs.getLong("qualified"),
                        rs.getLong("follow_up"), rs.getLong("recent"))).single();
    }

    /** Toggles the follow-up marker; returns false if the conversation does not exist. */
    public boolean toggleHandled(UUID id) {
        return jdbc.sql("UPDATE chat_conversation SET handled_at = CASE WHEN handled_at IS NULL THEN now() END WHERE id = :id")
                .param("id", id).update() == 1;
    }

    private static String select() {
        return """
                SELECT c.id, c.channel, c.created_at, c.updated_at, c.handoff_requested_at, c.handoff_reason, c.handled_at, c.forwarded_at,
                       COALESCE(l.status, 'NEW') AS status, COALESCE(l.persona, 'UNKNOWN') AS persona,
                       COALESCE(l.intent, 'UNKNOWN') AS intent, l.name, l.phone, l.email, l.city, l.project_type, l.spaces,
                       l.finish_interest, l.area_size, l.timeline, l.budget, l.callback_time,
                       (SELECT count(*) FROM chat_message m WHERE m.conversation_id = c.id) AS message_count,
                       (SELECT m.content FROM chat_message m WHERE m.conversation_id = c.id AND m.role = 'USER'
                        ORDER BY m.id DESC LIMIT 1) AS last_visitor_message
                """;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, LeadFilter f) {
        return spec.param("status", f.status()).param("persona", f.persona()).param("intent", f.intent())
                .param("channel", f.channel()).param("followUp", f.followUp()).param("q", f.q())
                .param("from", f.from() == null ? null : java.sql.Date.valueOf(f.from()))
                .param("to", f.to() == null ? null : java.sql.Date.valueOf(f.to()));
    }

    private LeadRow row(ResultSet rs, int i) throws SQLException {
        return new LeadRow(rs.getObject("id", UUID.class), rs.getString("channel"),
                ts(rs, "created_at"), ts(rs, "updated_at"),
                ts(rs, "handoff_requested_at"), rs.getString("handoff_reason"),
                ts(rs, "handled_at"), rs.getString("status"), rs.getString("persona"),
                rs.getString("intent"), rs.getString("name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("city"), rs.getString("project_type"), rs.getString("spaces"), rs.getString("finish_interest"),
                rs.getString("area_size"), rs.getString("timeline"), rs.getString("budget"), rs.getString("callback_time"),
                rs.getInt("message_count"), rs.getString("last_visitor_message"), ts(rs, "forwarded_at"));
    }

    /** Postgres returns UTC; operators read local time. */
    private OffsetDateTime ts(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(zone).toOffsetDateTime();
    }

    private List<ChatSource> sources(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<ChatSource>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
