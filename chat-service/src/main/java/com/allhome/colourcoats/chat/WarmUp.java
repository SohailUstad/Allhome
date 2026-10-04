package com.allhome.colourcoats.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The first request after startup pays for TLS handshakes, connection pools and JIT, which pushed the first
 * SalesIQ reply past its 5 s limit. Warm every hop once in the background (one embedding + a 1-token chat call).
 */
@Component
public class WarmUp {
    private com.allhome.colourcoats.flowlog.Telemetry telemetry = com.allhome.colourcoats.flowlog.Telemetry.local();
    @org.springframework.beans.factory.annotation.Autowired
    public void observability(com.allhome.colourcoats.flowlog.Telemetry telemetry) { this.telemetry = telemetry; }
    private final VectorStore vectorStore;
    private final KeywordRetriever keywordRetriever;
    private final ChatClient.Builder chatClientBuilder;
    private final boolean enabled;

    public WarmUp(VectorStore vectorStore, KeywordRetriever keywordRetriever, ChatClient.Builder chatClientBuilder,
                  @Value("${chat.warm-up:true}") boolean enabled) {
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;
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
                    telemetry.stage("startup.warmup", () -> {
                        vectorStore.similaritySearch(SearchRequest.builder().query("lime wash finishes").topK(1).build());
                        keywordRetriever.search("Marmorino", 1);
                        chatClientBuilder.build().prompt().user("Reply with the single word: ok").call().content();
                    });
                } catch (RuntimeException e) { telemetry.failure("startup.warmup.failed", e); }
            }
        });
    }
}
