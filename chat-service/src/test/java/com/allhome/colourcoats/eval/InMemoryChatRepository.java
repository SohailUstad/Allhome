package com.allhome.colourcoats.eval;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatRepository;
import com.allhome.colourcoats.chat.ChatSource;
import com.allhome.colourcoats.chat.Lead;
import java.util.*;

/** Keeps eval conversations out of the real chat tables while exercising the real ChatService. */
class InMemoryChatRepository extends ChatRepository {
    private final Map<UUID, List<StoredMessage>> messages = new HashMap<>();
    private final Map<UUID, Lead> leads = new HashMap<>();
    private final Map<UUID, String> handoffs = new HashMap<>();

    InMemoryChatRepository() {
        super(null, null);
    }

    @Override public void touchConversation(UUID id, Channel channel) { messages.computeIfAbsent(id, k -> new ArrayList<>()); }
    @Override public boolean exists(UUID id) { return messages.containsKey(id); }
    @Override public void saveUserMessage(UUID id, String content) { messages.get(id).add(new StoredMessage("USER", content, null)); }

    @Override
    public void saveAssistantMessage(UUID id, String content, boolean handoff, List<ChatSource> sources,
                                     String model, Integer promptTokens, Integer completionTokens, UUID promptVersionId) {
        messages.get(id).add(new StoredMessage("ASSISTANT", content, null));
    }

    @Override public void requestHandoff(UUID id, String reason) { handoffs.put(id, reason); }
    @Override public boolean isForwarded(UUID id) { return false; }
    @Override public void markForwarded(UUID id) { }
    @Override public Lead findLead(UUID id) { return leads.getOrDefault(id, Lead.EMPTY); }
    @Override public void saveLead(UUID id, Lead lead) { leads.put(id, lead); }

    @Override
    public List<StoredMessage> recentMessages(UUID id, int limit) {
        var all = messages.getOrDefault(id, List.of());
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    String handoffReason(UUID id) { return handoffs.get(id); }
}
