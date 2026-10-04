package com.allhome.colourcoats.chat;

import com.allhome.colourcoats.retrieval.KnowledgeSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The first request after startup pays for TLS handshakes, connection pools and JIT, which pushed the first
 * SalesIQ reply past its 5 s limit. Warm every hop once in the background (one search + a 1-token chat call).
 */
@Component
public class WarmUp {
    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);
    private final KnowledgeSearch knowledgeSearch;
    private final ChatClient.Builder chatClientBuilder;
    private final boolean enabled;

    public WarmUp(KnowledgeSearch knowledgeSearch, ChatClient.Builder chatClientBuilder,
                  @Value("${chat.warm-up:true}") boolean enabled) {
        this.knowledgeSearch = knowledgeSearch;
        this.chatClientBuilder = chatClientBuilder;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) return;
        Thread.ofVirtual().name("chat-warm-up").start(() -> {
            try (var scope = com.allhome.colourcoats.flowlog.TraceContext.open()) {
                com.allhome.colourcoats.flowlog.TraceContext.current().trafficKind = "warmup";
                try {
                    knowledgeSearch.search("lime wash finishes");
                    chatClientBuilder.build().prompt().user("Reply with the single word: ok").call().content();
                } catch (RuntimeException e) { log.warn("Warm-up failed: {}", e.toString()); }
            }
        });
    }
}
