package com.allhome.colourcoats.eval;

import com.allhome.colourcoats.chat.Lead;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Free, offline guard for the eval set itself: runs in every normal build. */
class EvalCasesTests {
    static final Set<String> LEAD_FIELDS = Set.of("name", "phone", "email", "city", "projectType", "spaces",
            "finishInterest", "areaSize", "timeline", "budget", "callbackTime");

    List<EvalCase> cases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/evals/cases.json")) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<>() {});
        }
    }

    static final Set<String> CATEGORIES = Set.of("grounding", "pricing-timeline", "unknown-question", "visitor-type",
            "incomplete-lead", "handoff-callback", "channel", "conversation", "safety");

    @Test void casesFileIsValid() throws Exception {
        var ids = new HashSet<String>();
        for (var c : cases()) {
            assertThat(ids.add(c.id())).as("duplicate id %s", c.id()).isTrue();
            assertThat(c.turns()).as(c.id()).isNotEmpty();
            assertThat(CATEGORIES).as(c.id()).contains(c.category());
            c.resolvedChannel(); // throws on an unknown channel name
            var e = c.expect();
            assertThat(e).as(c.id()).isNotNull();
            for (var patterns : java.util.Arrays.asList(e.mustNotMatch(), e.finalMustNotMatch())) {
                if (patterns == null) continue;
                for (String regex : patterns) {
                    // "" written with one backslash in JSON is a backspace character, silently breaking the regex.
                    assertThat(regex.chars().noneMatch(Character::isISOControl)).as("%s: control char in /%s/", c.id(), regex).isTrue();
                    Pattern.compile(regex);
                }
            }
            if (e.persona() != null) assertThat(Lead.PERSONAS).as(c.id()).contains(e.persona());
            if (e.intent() != null) assertThat(Lead.INTENTS).as(c.id()).contains(e.intent());
            if (e.leadStatus() != null) assertThat(List.of("NEW", "ENGAGED", "QUALIFIED")).as(c.id()).contains(e.leadStatus());
            if (e.lead() != null) assertThat(LEAD_FIELDS).as(c.id()).containsAll(e.lead().keySet());
            if (e.minMentions() != null) assertThat(e.mustMentionAny()).as("%s: minMentions needs mustMentionAny", c.id())
                    .isNotNull().hasSizeGreaterThanOrEqualTo(e.minMentions());
        }
        assertThat(cases().stream().filter(EvalCase::critical)).isNotEmpty();
    }

    /** The suite must keep covering every category, both channels and every persona. */
    @Test void casesCoverCategoriesChannelsAndPersonas() throws Exception {
        var all = cases();
        assertThat(all).hasSizeGreaterThanOrEqualTo(8);
        assertThat(all.stream().map(EvalCase::category).distinct()).containsExactlyInAnyOrderElementsOf(CATEGORIES);
        assertThat(all.stream().map(EvalCase::resolvedChannel).distinct())
                .containsExactlyInAnyOrder(com.allhome.colourcoats.chat.Channel.values());
        var personas = all.stream().map(c -> c.expect().persona()).filter(java.util.Objects::nonNull).toList();
        assertThat(personas).containsAll(Lead.PERSONAS.stream().filter(p -> !p.equals("UNKNOWN")).toList());
    }

    /** The real ungrounded reply observed on 2026-10-03 must be caught by the exterior case. */
    @Test void exteriorCaseCatchesKnownBadReply() throws Exception {
        String knownBad = "For exterior walls, it's important to use weather-resistant, anti-fungal, and UV-stable paints "
                + "to withstand the elements. At ColourCoats, we specify premium brands like Asian Paints Royale, Nippon, "
                + "Dulux Ambiance, and Benjamin Moore, chosen carefully for durability and finish.";
        var exterior = cases().stream().filter(c -> c.id().equals("exterior-paint-no-invented-products")).findFirst().orElseThrow();
        assertThat(exterior.expect().mustNotMatch()).anyMatch(regex -> Pattern.compile(regex).matcher(knownBad).find());
    }

    /** Bad replies observed in the first live eval run (2026-10-04) must stay caught. */
    @Test void casesCatchBadRepliesFromFirstLiveRun() throws Exception {
        var all = cases();
        var builder = all.stream().filter(c -> c.id().equals("builder-developer-bulk")).findFirst().orElseThrow().expect();
        assertThat(builder.mustNotMatch()).anyMatch(r -> Pattern.compile(r).matcher(
                "We can handle premium architectural finishes and painting with our own trained artisans across India").find());
        var corporate = all.stream().filter(c -> c.id().equals("corporate-office-examples")).findFirst().orElseThrow().expect();
        assertThat(corporate.minMentions()).isGreaterThanOrEqualTo(2);
        var marmorino = all.stream().filter(c -> c.id().equals("email-only-missing-city")).findFirst().orElseThrow().expect();
        assertThat(marmorino.finalMustNotMatch()).anyMatch(r -> Pattern.compile(r).matcher(
                "We don't have information about Marmorino finish in our signature collection.").find());
    }

    /** Judge quotes must be found despite punctuation/case differences, and invented quotes must not. */
    @Test void judgeEvidenceMatchingIsTolerantButNotLoose() {
        String knowledge = EvalRunner.normalize("An hour with us reveals what samples cannot. Walk through 60+ curated finishes, "
                + "feel textures under your hand, discuss your project with our senior consultants — all");
        assertThat(knowledge).contains(EvalRunner.normalize("Walk through 60+ curated finishes, feel textures under your hand"));
        assertThat(knowledge).contains(EvalRunner.normalize("discuss your project with our Senior Consultants – all"));
        assertThat(knowledge).doesNotContain(EvalRunner.normalize("Walk through 80+ curated finishes"));
    }

    /** The long, multi-question reply the user flagged (2026-10-04) must fail the style checks; a short one must pass. */
    @Test void styleChecksCatchLongMultiQuestionReplies() {
        String flagged = "ColourCoats works with all project types including corporate spaces, offering premium wall textures and "
                + "finishes tailored to each space. To help you best, could you share more about the specific corporate projects "
                + "or spaces you want to target and your timeline for showcasing our finishes? We can then arrange a specialist consultation.";
        String good = "Our metallic coatings are specified for feature walls and ceilings, so they could suit a reception. Which city is the office in?";
        assertThat(EvalRunner.styleChecks(List.of(good))).allMatch(CaseResult.Check::passed);
        assertThat(EvalRunner.styleChecks(List.of(good, flagged)))
                .anyMatch(c -> !c.passed() && c.name().contains("words"));
        assertThat(EvalRunner.styleChecks(List.of("Which room? And when do you want to start?")))
                .anyMatch(c -> !c.passed() && c.name().contains("question"));
    }

    /** Real reply 2026-10-04: English answer to Hinglish, jumping to the city question instead of the ideas asked for. */
    @Test void followTheLeadCaseCatchesObservedReply() throws Exception {
        String observed = "We have theme-based effects that can make each room unique, like textured lime washes for bedrooms "
                + "and metallic coatings for feature walls. Which city is your site in?";
        var hinglish = cases().stream().filter(c -> c.id().equals("hinglish-room-suggestions")).findFirst().orElseThrow().expect();
        assertThat(hinglish.finalMustNotMatch()).anyMatch(r -> Pattern.compile(r).matcher(observed).find());
        String lower = observed.toLowerCase();
        assertThat(hinglish.mustMentionAny().stream().filter(lower::contains).count()).isLessThan(hinglish.minMentions());
        // A good Hinglish answer about taste passes both.
        String good = "Bedroom aur hall ke liye lime wash ka soft look badhiya rahega, kitchen mein wood coating. Aapko earthy tones pasand hain?";
        assertThat(hinglish.finalMustNotMatch()).noneMatch(r -> Pattern.compile(r).matcher(good).find());
        assertThat(hinglish.mustMentionAny().stream().filter(good.toLowerCase()::contains).count()).isGreaterThanOrEqualTo(hinglish.minMentions());
    }

    @Test void safetyPatternsCatchInventedNumbersButNotHonestReplies() throws Exception {
        var all = cases();
        var pricing = all.stream().filter(c -> c.id().equals("pricing-handoff")).findFirst().orElseThrow().expect().mustNotMatch();
        var timeline = all.stream().filter(c -> c.id().equals("timeline-not-invented")).findFirst().orElseThrow().expect().mustNotMatch();
        String honest = "I don't have pricing or timeline details. A ColourCoats specialist will confirm after a site visit.";
        assertThat(pricing).anyMatch(r -> Pattern.compile(r).matcher("It costs around Rs 85 per sq ft.").find());
        assertThat(pricing).anyMatch(r -> Pattern.compile(r).matcher("Roughly ₹120/sq ft").find());
        assertThat(timeline).anyMatch(r -> Pattern.compile(r).matcher("Usually 7-10 days for a 3BHK").find());
        assertThat(pricing).noneMatch(r -> Pattern.compile(r).matcher(honest).find());
        assertThat(timeline).noneMatch(r -> Pattern.compile(r).matcher(honest).find());
    }
}
