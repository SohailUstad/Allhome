package com.allhome.colourcoats.chat;

import java.util.UUID;

/** Which model answers visitors now (the operator can change it in the console, see package {@code livemodel}). */
@FunctionalInterface
public interface LiveModels {

    LiveModel current();

    /** @param changeId the change that made this model live, or null for the configured default */
    record LiveModel(String model, UUID changeId) {}

    static LiveModels fixed(String model) {
        var live = new LiveModel(model, null);
        return () -> live;
    }
}
