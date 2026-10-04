package com.allhome.colourcoats.chat;

import java.util.Set;

/**
 * Everything learned about the visitor so far. One row per conversation; each turn's extraction is merged in,
 * so a detail given once is never lost and the model is never asked to repeat it.
 */
public record Lead(String persona, String intent, String name, String phone, String email, String city,
                   String projectType, String spaces, String finishInterest, String areaSize, String timeline,
                   String budget, String callbackTime) {

    public static final Set<String> PERSONAS = Set.of("HOMEOWNER", "ARCHITECT_OR_DESIGNER", "BUILDER_OR_DEVELOPER",
            "COMMERCIAL_CLIENT", "PARTNER_PROSPECT", "EXISTING_CUSTOMER", "VENDOR_OR_JOB_SEEKER", "UNKNOWN");
    public static final Set<String> INTENTS = Set.of("BROWSING", "RESEARCHING", "PLANNING_PROJECT", "READY_TO_ENGAGE",
            "SUPPORT", "UNKNOWN");
    private static final int MAX_FIELD = 300;

    public static final Lead EMPTY = new Lead("UNKNOWN", "UNKNOWN", null, null, null, null, null, null, null, null, null, null, null);

    /** Newer non-blank values win; a classification of UNKNOWN or an unrecognised label never overwrites a known one. */
    public Lead merge(String newPersona, String newIntent, ModelAnswer.LeadDetails d) {
        if (d == null) d = new ModelAnswer.LeadDetails(null, null, null, null, null, null, null, null, null, null, null);
        return new Lead(label(newPersona, persona, PERSONAS), label(newIntent, intent, INTENTS),
                pick(d.name(), name), pick(d.phone(), phone), pick(d.email(), email), pick(d.city(), city),
                pick(d.projectType(), projectType), pick(d.spaces(), spaces), pick(d.finishInterest(), finishInterest),
                pick(d.areaSize(), areaSize), pick(d.timeline(), timeline), pick(d.budget(), budget),
                pick(d.callbackTime(), callbackTime));
    }

    /** QUALIFIED = the five minimum fields sales needs: name, contact number, city, persona, requirement. */
    public String status() {
        boolean knownPersona = persona != null && !"UNKNOWN".equals(persona);
        if (name != null && phone != null && city != null && knownPersona && hasRequirement()) return "QUALIFIED";
        if (phone != null || email != null || name != null || city != null || knownPersona || hasRequirement() || timeline != null) return "ENGAGED";
        return "NEW";
    }

    public boolean hasRequirement() {
        return projectType != null || spaces != null || finishInterest != null;
    }

    private static String pick(String incoming, String current) {
        if (incoming == null || incoming.isBlank() || incoming.strip().equalsIgnoreCase("null")) return current;
        String value = incoming.strip();
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value;
    }

    private static String label(String incoming, String current, Set<String> allowed) {
        if (incoming == null) return current;
        String value = incoming.strip().toUpperCase();
        return allowed.contains(value) && !"UNKNOWN".equals(value) ? value : current;
    }
}
