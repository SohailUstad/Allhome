package com.allhome.colourcoats.flowlog;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Application identity; explicitly attached on executor threads, never inherited implicitly. */
public final class TraceContext {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();
    public final String requestId = UUID.randomUUID().toString();
    public final String operationId = UUID.randomUUID().toString();
    public UUID turnId;
    public UUID conversationId;
    public String channel = "SYSTEM";
    public String externalEventHash;
    public String archiveHash;
    public String trafficKind = "customer";
    public String evalCase;
    public Integer evalRun;
    public final TurnCompletion completion = new TurnCompletion();

    public static TraceContext current() { return CURRENT.get(); }

    public static Scope open() {
        return current() == null ? attach(new TraceContext()) : () -> {};
    }

    public static Scope attach(TraceContext context) {
        TraceContext previous = CURRENT.get();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        CURRENT.set(context);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
        };
    }

    /** Runs work on another thread with this thread's context (and log MDC) attached, e.g. the SalesIQ chat worker. */
    public static <T> java.util.concurrent.Callable<T> propagate(java.util.concurrent.Callable<T> work) {
        TraceContext context = current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            try (var attached = attach(context == null ? new TraceContext() : context)) {
                if (mdc != null) MDC.setContextMap(mdc);
                return work.call();
            }
        };
    }

    public void bind(UUID conversation, String channel) {
        if (turnId == null) turnId = UUID.randomUUID();
        conversationId = conversation;
        this.channel = channel;
    }

    public static void outcome(String outcome) { MDC.put("business_outcome", outcome); }

    public interface Scope extends AutoCloseable { @Override void close(); }
}
