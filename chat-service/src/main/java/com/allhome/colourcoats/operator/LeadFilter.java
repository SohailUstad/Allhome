package com.allhome.colourcoats.operator;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.Lead;
import java.time.LocalDate;
import java.util.Set;

/**
 * Operator filters from query parameters. Unknown values are dropped (treated as "any") rather than passed on,
 * so a hand-edited URL can never produce an error page or reach SQL as anything but a bound parameter.
 */
public record LeadFilter(String status, String persona, String intent, String channel, String followUp, String q,
                         LocalDate from, LocalDate to, String sort, int page) {
    public static final Set<String> STATUSES = Set.of("NEW", "ENGAGED", "QUALIFIED");
    public static final Set<String> FOLLOW_UPS = Set.of("needed", "handled", "none");
    public static final Set<String> SORTS = Set.of("updated", "created", "handoff");

    public static LeadFilter of(String status, String persona, String intent, String channel, String followUp, String q,
                                LocalDate from, LocalDate to, String sort, Integer page) {
        return new LeadFilter(allowed(status, STATUSES), allowed(persona, Lead.PERSONAS), allowed(intent, Lead.INTENTS),
                allowed(channel, channels()), allowed(followUp, FOLLOW_UPS),
                q == null || q.isBlank() ? null : q.strip().substring(0, Math.min(q.strip().length(), 100)),
                from, to, sort != null && SORTS.contains(sort) ? sort : "updated",
                page == null || page < 1 ? 1 : Math.min(page, 10_000));
    }

    public static Set<String> channels() {
        return java.util.Arrays.stream(Channel.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }

    private static String allowed(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    public boolean active() {
        return status != null || persona != null || intent != null || channel != null || followUp != null
                || q != null || from != null || to != null;
    }
}
