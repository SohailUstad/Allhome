package com.allhome.colourcoats.chat;

import com.allhome.colourcoats.chat.HandoffPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HandoffPolicyTests {
    static boolean fires(String visitor, String reply) {
        return !HandoffPolicy.ruleReasons(visitor).isEmpty();
    }

    /** Only unmistakable requests for a human, and complaints about ColourCoats' own work, are forced by rule. */
    @Test void explicitRequestsForAPersonOrCallAndComplaintsFire() {
        assertThat(fires("Can someone from your team call me?", "")).isTrue();
        assertThat(fires("please call me back", "")).isTrue();
        assertThat(fires("I want to talk to a human", "")).isTrue();
        assertThat(fires("can I speak with someone from your team", "")).isTrue();
        assertThat(fires("connect me to a specialist", "")).isTrue();
        assertThat(fires("mujhe kisi se baat karni hai", "")).isTrue();
        assertThat(fires("call kar do please", "")).isTrue();
        assertThat(fires("The texture you applied is peeling, very disappointed", "")).isTrue();
        assertThat(fires("I want to file a complaint", "")).isTrue();
        assertThat(fires("your painters damaged my floor", "")).isTrue();
        assertThat(fires("aapke log ka kaam bilkul kharab hai", "")).isTrue();
    }

    /** These go to the model (continue → offer → handoff); keyword rules used to transfer them by mistake. */
    @Test void ordinarySalesTalkIsLeftToTheModel() {
        assertThat(fires("How much does lime wash cost per square foot?", "")).isFalse();
        assertThat(fires("Lime wash ka rate kya hai per sq ft?", "")).isFalse();
        assertThat(fires("price?", "")).isFalse();
        assertThat(fires("my budget is around 2 lakh", "")).isFalse();
        assertThat(fires("kitne rooms ka kaam hai, 3 rooms", "")).isFalse();
        assertThat(fires("our timeline is March", "")).isFalse();
        assertThat(fires("I love the human touch in your work", "")).isFalse();
        assertThat(fires("my walls are peeling, what do you suggest?", "")).isFalse();
        assertThat(fires("can you suggest something for my bad walls", "")).isFalse();
        assertThat(fires("don't call me, just message here", "")).isFalse();
        assertThat(fires("call mat karo, yahin batao", "")).isFalse();
        assertThat(fires("can you do this in blue?", "")).isFalse();
        assertThat(fires("are you a human?", "")).isFalse();
        assertThat(fires("I'm interested in opening a ColourCoats franchise in Jaipur", "")).isFalse();
        assertThat(fires("Do you give a 10 year warranty?", "")).isFalse();
    }

    /**
     * Real SalesIQ chat 2026-10-04: a designer asked for a matte finish recommendation and was transferred to an
     * operator mid-conversation. Asking for a recommendation, or the bot mentioning a specialist, is not a reason.
     */
    @Test void recommendationRequestsAndSpecialistMentionsDoNotForceATransfer() {
        assertThat(fires("I'm designing a luxury apartment and need a matte wall finish for the living room that's easy "
                + "to clean but doesn't have that plastic-looking sheen. What would you recommend?",
                "Our lime-washed textures give a matte, natural finish. A specialist can confirm cleaning care.")).isFalse();
        assertThat(fires("x", "We don't have information about EMI on our site.")).isFalse();
    }

    /** Real SalesIQ chat 2026-10-04: the bot asked a question and the chat was transferred right after it. */
    @Test void transferReplyNeverAsksAQuestion() {
        String observed = "I don't have info about our founder here, but I can connect you with someone who can share more. "
                + "Meanwhile, what kind of project are you thinking about?";
        assertThat(HandoffPolicy.transferReply(observed, "Connecting you now."))
                .isEqualTo("I don't have info about our founder here, but I can connect you with someone who can share more.");
        // Nothing left but a question: use the transfer message.
        assertThat(HandoffPolicy.transferReply("Which city is the project in?", "Connecting you now.")).isEqualTo("Connecting you now.");
        // Answer without any "connecting" wording: transfer message appended.
        assertThat(HandoffPolicy.transferReply("Pricing depends on the finish and your walls. What's your budget?", "Connecting you now."))
                .isEqualTo("Pricing depends on the finish and your walls. Connecting you now.");
        assertThat(HandoffPolicy.transferReply("Let me connect you with a specialist!", "X")).isEqualTo("Let me connect you with a specialist!");
    }

    @Test void ordinaryQuestionsAndOffersDoNotFire() {
        assertThat(fires("Where in a home can lime wash be used?",
                "Lime wash is best specified for living rooms, master bedrooms and feature walls. Which room are you thinking of?")).isFalse();
        assertThat(fires("Which paint brands do you work with?",
                "We work with Asian Paints Royale, Nippon, Dulux, Benjamin Moore and Farrow & Ball.")).isFalse();
        // Words that merely contain trigger words must not fire.
        assertThat(fires("We want accurate colours to decorate our premium flat", "Lovely, which rooms?")).isFalse();
        assertThat(fires("Do you do Marmorino?", "Yes, Marmorino is one of our lime plaster textures. Would you like a consultation?")).isFalse();
    }
}
