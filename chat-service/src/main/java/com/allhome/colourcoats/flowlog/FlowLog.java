package com.allhome.colourcoats.flowlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Evidence log of one message's round trip: SalesIQ request → OpenAI request → OpenAI response → SalesIQ response.
 * Unlike {@link Telemetry}, this DOES record full payloads (visitor text, prompts, replies), so treat the log file
 * as personal data. Turn it off with {@code logging.level.flow: OFF}.
 *
 * Every entry carries request_id (one webhook call, also the X-Request-Id header), conversation_id (all turns of
 * one chat) and, once known, turn_id, so the four steps can be grouped and correlated.
 */
public final class FlowLog {
    private static final Logger LOG = LoggerFactory.getLogger("flow");

    private FlowLog() {}

    public static void log(String step, Object... fields) {
        if (!LOG.isInfoEnabled()) return;
        LoggingEventBuilder builder = LOG.atInfo().addKeyValue("flow_step", step);
        TraceContext context = TraceContext.current();
        if (context != null) {
            builder.addKeyValue("request_id", context.requestId).addKeyValue("channel", context.channel);
            if (context.conversationId != null) builder.addKeyValue("conversation_id", context.conversationId.toString());
            if (context.turnId != null) builder.addKeyValue("turn_id", context.turnId.toString());
        }
        for (int i = 0; i < fields.length; i += 2) builder.addKeyValue((String) fields[i], fields[i + 1]);
        builder.log(step);
    }

    public static boolean enabled() { return LOG.isInfoEnabled(); }
}
