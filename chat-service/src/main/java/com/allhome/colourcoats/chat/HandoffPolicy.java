package com.allhome.colourcoats.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic safety net for routing to a human. The MODEL decides handoff (continue → offer → handoff, see the
 * system prompt); these rules only catch the unmistakable cases the model must never miss: the visitor explicitly
 * asks for a person or a call, or complains about ColourCoats' own work. Price, timeline, visits, partnerships etc.
 * are left to the model, because keyword matches there fired on ordinary sales talk ("my budget is…", "kitne rooms",
 * "walls are peeling, what do you suggest?"). Rules only ever turn handoff ON, never off.
 */
public final class HandoffPolicy {
    public static final String VERSION = "2026-10-04.2";
    private HandoffPolicy() {}

    private record Rule(String reason, Pattern pattern, Pattern unless) {}

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Pattern NO_CALL = p("\\b(don'?t|do not|dont|no need to|never|mat)\\s+(\\w+\\s+)?call\\b|\\bcall\\s+(mat|nahi|na)\\b|\\bno calls?\\b");

    private static final List<Rule> VISITOR_RULES = List.of(
            new Rule("asked for a person", p(
                    "\\b(talk|speak|chat|connect)( me)? (to|with) (a |an |some |your |the )?(human|person|someone|somebody|agent|representative|executive|specialist|expert|team|sales)\\b"
                    + "|\\b(real|actual|live) (person|human|agent)\\b|\\bhuman (agent|support|being)\\b"
                    + "|\\bbaat (karni|karna|karni hai|karao|kara do|karwa do|krni|krna)\\b|\\bkisi se baat\\b"), null),
            new Rule("asked for a call", p(
                    "\\b(call me|call back|callback|give me a call|ring me|(someone|somebody|team) (to |can |could |please )?call"
                    + "|call (karo|kar do|kardo|karna|karwa do|kijiye)|phone (karo|kar do|kijiye))\\b"), NO_CALL),
            // A complaint must be about ColourCoats' own work, not a prospect describing their current walls.
            new Rule("complaint about ColourCoats work", p(
                    "\\b(complaint|complain|refund)\\b"
                    + "|\\b(your (team|people|workers|painters|work|job)|aapne|aapke (log|painters|workers|team|kaam)|aapka kaam|applied by you|you applied|you did)\\b.{0,60}"
                    + "\\b(peeling|cracking|cracked|damaged?|poor|bad|not happy|unhappy|disappointed|kharab|bekar)\\b"
                    + "|\\b(peeling|cracking|cracked|damaged?|poor|bad|not happy|unhappy|disappointed|kharab|bekar)\\b.{0,60}"
                    + "\\b(you applied|you did|your (team|people|workers|painters|work))\\b"), null));

    // No rules on the bot's own reply text: since a handoff now transfers the live chat and ends the AI's part,
    // "a specialist can confirm that" in a good answer must not force a transfer (observed 2026-10-04).

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern SAYS_CONNECTING = p("\\b(connect|connecting|transfer|specialist|team member|colleague)\\b");

    /**
     * The last bot message before a LIVE transfer must not ask anything: the AI will not be there to read the answer.
     * Drops question sentences (and "Meanwhile, ..." lead-ins that set them up) and makes sure the visitor is told
     * they're being connected.
     */
    public static String transferReply(String reply, String transferMessage) {
        var kept = new ArrayList<String>();
        for (String sentence : SENTENCE_BREAK.split(reply == null ? "" : reply.strip())) {
            String s = sentence.strip();
            if (s.isEmpty() || s.endsWith("?")) continue;
            kept.add(s);
        }
        String text = String.join(" ", kept);
        if (text.isBlank()) return transferMessage;
        return SAYS_CONNECTING.matcher(text).find() ? text : text + " " + transferMessage;
    }

    /** Reasons this visitor message needs a human by rule (what they ASKED for); empty when no rule fired. */
    public static List<String> ruleReasons(String visitorMessage) {
        List<String> reasons = new ArrayList<>();
        for (Rule rule : VISITOR_RULES) {
            if (rule.pattern().matcher(visitorMessage).find()
                    && (rule.unless() == null || !rule.unless().matcher(visitorMessage).find())) reasons.add(rule.reason());
        }
        return reasons;
    }
}
