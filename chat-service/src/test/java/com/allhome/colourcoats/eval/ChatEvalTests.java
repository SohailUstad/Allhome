package com.allhome.colourcoats.eval;

import com.allhome.colourcoats.chat.Channel;
import com.allhome.colourcoats.chat.ChatService;
import com.allhome.colourcoats.chat.Lead;
import com.allhome.colourcoats.chat.SystemPrompts;
import com.allhome.colourcoats.prompt.PromptService;
import com.allhome.colourcoats.retrieval.KnowledgeSearch;
import com.allhome.colourcoats.retrieval.RetrievalProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural evals against the REAL pipeline: pgvector retrieval + OpenAI (a few cents per run).
 * Chat persistence is in-memory so eval traffic never reaches the real chat/lead tables.
 *
 * Run:  ./mvnw test -Pevals        (options: -Deval.repeat=3 -Deval.minPassRate=0.85 -Deval.judge=false
 *                                   -Deval.prompt=active|file|<version id>)
 * Gate: every run of every critical case must pass, and the overall check pass rate must reach minPassRate.
 * Report: target/evals/latest.md (human) and latest.json (diffable), plus timestamped copies.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatEvalTests {
    @Autowired ChatClient.Builder chatClientBuilder;
    @Autowired KnowledgeSearch knowledgeSearch;
    @Autowired RetrievalProperties retrieval;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PromptService prompts;
    @Value("classpath:prompts/sales-system.md") Resource promptFile;
    SystemPrompts.SystemPrompt systemPrompt;
    String promptLabel;
    @Value("classpath:evals/cases.json") Resource casesFile;
    @Value("classpath:evals/grounding-judge.md") Resource judgePrompt;
    @Value("${chat.history-size}") int historySize;
    @Value("${spring.ai.openai.chat.model}") String model;
    // A stronger model than the bot's: judging is harder than answering, and runs only at eval time.
    String judgeModel = System.getProperty("eval.judgeModel", "gpt-4.1");

    static final String JUDGE_CHECK = "grounded (judge)";
    static final String STYLE_PREFIX = "style: ";
    // "Short, human, one question at a time" applies to every reply of every case.
    static final int MAX_WORDS = Integer.getInteger("eval.maxWords", 45);
    static final int MAX_QUESTIONS = 1;

    record Expect(List<String> sourcesInclude, Boolean handoff, String persona, String intent, String leadStatus,
                  Map<String, String> lead, List<String> mustMentionAny, List<String> mustNotMatch,
                  List<String> finalMustNotMatch, Integer maxReplyChars, Boolean grounded, Integer minMentions) {}
    record EvalCase(String id, String category, String description, boolean critical, String channel,
                    List<String> turns, Expect expect) {
        Channel resolvedChannel() { return channel == null ? Channel.ZOHO_SALESIQ : Channel.valueOf(channel); }
    }
    record Claim(String claim, String type, boolean supported, List<String> evidence) {}
    record JudgeOutput(List<Claim> claims) {}
    record Verdict(boolean grounded, List<String> problems) {}
    record Check(String name, boolean passed, String detail) {}
    record RunResult(String caseId, String category, boolean critical, String channel, int run, List<String> turns,
                     List<String> replies, boolean handoff, Lead lead, List<String> sources, List<Check> checks, String error) {
        boolean passed() { return error == null && checks.stream().allMatch(Check::passed); }
        /** Critical gating uses deterministic behaviour checks; the noisy judge and style checks count toward the pass rate. */
        boolean deterministicPassed() {
            return error == null && checks.stream()
                    .filter(c -> !c.name().equals(JUDGE_CHECK) && !c.name().startsWith(STYLE_PREFIX)).allMatch(Check::passed);
        }
    }

    @Test
    void chatBehaviourMeetsEvalGate() throws Exception {
        int repeat = Integer.getInteger("eval.repeat", 1);
        double minPassRate = Double.parseDouble(System.getProperty("eval.minPassRate", "0.85"));
        boolean useJudge = !"false".equals(System.getProperty("eval.judge"));
        String only = System.getProperty("eval.case");
        selectPrompt(System.getProperty("eval.prompt", "active"));
        List<EvalCase> cases = json.readValue(casesFile.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
        if (only != null && !only.isBlank()) cases = cases.stream().filter(c -> c.id().equals(only)).toList();
        var judge = chatClientBuilder.build();
        String judgeSystem = judgePrompt.getContentAsString(StandardCharsets.UTF_8);

        List<RunResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            for (int run = 1; run <= repeat; run++) {
                System.out.printf("running %-40s #%d%n", c.id(), run);
                results.add(runCase(c, run, useJudge ? judge : null, judgeSystem));
            }
        }

        long checks = results.stream().mapToLong(r -> r.checks().size() + (r.error() == null ? 0 : 1)).sum();
        long passedChecks = results.stream().flatMap(r -> r.checks().stream()).filter(Check::passed).count();
        double passRate = checks == 0 ? 1 : (double) passedChecks / checks;
        var criticalFailures = results.stream().filter(r -> r.critical() && !r.deterministicPassed()).map(RunResult::caseId).distinct().toList();
        boolean gatePassed = criticalFailures.isEmpty() && passRate >= minPassRate;

        var meta = meta(repeat, useJudge, minPassRate, passRate, gatePassed, criticalFailures);
        Path report = writeReports(meta, results);
        System.out.println(summary(meta, results, report));

        assertThat(criticalFailures).as("critical eval cases failed, see %s", report).isEmpty();
        assertThat(passRate).as("check pass rate below gate, see %s", report).isGreaterThanOrEqualTo(minPassRate);
    }

    /** "active" (what visitors get now), "file" (the git file) or a version id (e.g. a draft before activating it). */
    private void selectPrompt(String choice) throws Exception {
        switch (choice) {
            case "active" -> {
                systemPrompt = prompts.active();
                promptLabel = "v" + systemPrompt.versionNumber() + " (active)";
            }
            case "file" -> {
                systemPrompt = new SystemPrompts.SystemPrompt(null, null, promptFile.getContentAsString(StandardCharsets.UTF_8));
                promptLabel = "file";
            }
            default -> {
                var version = prompts.version(UUID.fromString(choice));
                systemPrompt = new SystemPrompts.SystemPrompt(version.getId(), version.getVersionNumber(), version.getContent());
                promptLabel = "v" + version.getVersionNumber() + " (" + version.getStatus().name().toLowerCase() + ")";
            }
        }
        System.out.println("prompt: " + promptLabel);
    }

    private RunResult runCase(EvalCase c, int run, ChatClient judge, String judgeSystem) throws Exception {
        var repository = new InMemoryChatRepository();
        var prompt = systemPrompt;
        var service = new ChatService(chatClientBuilder, knowledgeSearch, repository, TransactionOperations.withoutTransaction(),
                () -> prompt, historySize, true, "Let me connect you with one of our specialists now.");
        UUID id = UUID.randomUUID();
        List<String> replies = new ArrayList<>();
        ChatService.Reply last = null;
        try {
            for (String turn : c.turns()) {
                try (var scope = com.allhome.colourcoats.flowlog.TraceContext.open()) {
                    var context = com.allhome.colourcoats.flowlog.TraceContext.current();
                    context.trafficKind = "eval";
                    context.evalCase = c.id();
                    context.evalRun = run;
                    last = service.chat(id, turn, c.resolvedChannel());
                }
                replies.add(last.reply());
            }
        } catch (RuntimeException ex) {
            return new RunResult(c.id(), c.category(), c.critical(), c.resolvedChannel().name(), run, c.turns(), replies,
                    false, repository.findLead(id), List.of(), List.of(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        Lead lead = repository.findLead(id);
        Expect e = c.expect();
        String finalReply = last.reply();
        List<Check> checks = new ArrayList<>();
        var sourceUrls = last.sources().stream().map(s -> String.valueOf(s.url())).toList();

        if (e.sourcesInclude() != null) for (String expected : e.sourcesInclude()) {
            checks.add(new Check("retrieval " + expected, sourceUrls.stream().anyMatch(u -> u.contains(expected)),
                    "sources=" + sourceUrls));
        }
        if (e.handoff() != null) checks.add(new Check("handoff=" + e.handoff(), e.handoff() == last.handoff(),
                "got " + last.handoff() + (repository.handoffReason(id) == null ? "" : " (" + repository.handoffReason(id) + ")")));
        if (e.persona() != null) checks.add(new Check("persona=" + e.persona(), e.persona().equals(lead.persona()), "got " + lead.persona()));
        if (e.intent() != null) checks.add(new Check("intent=" + e.intent(), e.intent().equals(lead.intent()), "got " + lead.intent()));
        if (e.leadStatus() != null) checks.add(new Check("leadStatus=" + e.leadStatus(), e.leadStatus().equals(lead.status()), "got " + lead.status()));
        if (e.lead() != null) e.lead().forEach((field, expected) -> {
            String actual = leadField(lead, field);
            checks.add(new Check("lead." + field + "~" + expected,
                    actual != null && actual.toLowerCase().contains(expected.toLowerCase()), "got " + actual));
        });
        if (e.mustMentionAny() != null) {
            String reply = finalReply.toLowerCase();
            int needed = e.minMentions() == null ? 1 : e.minMentions();
            var found = e.mustMentionAny().stream().filter(m -> reply.contains(m.toLowerCase())).toList();
            checks.add(new Check(needed == 1 ? "mentions any " + e.mustMentionAny()
                    : "mentions at least " + needed + " of " + e.mustMentionAny(), found.size() >= needed, "found " + found));
        }
        // Safety patterns apply to every reply in the conversation; finalMustNotMatch only to the last one.
        if (e.mustNotMatch() != null) for (String regex : e.mustNotMatch()) {
            var pattern = Pattern.compile(regex);
            var hit = replies.stream().map(r -> pattern.matcher(r)).filter(java.util.regex.Matcher::find).map(java.util.regex.Matcher::group).findFirst();
            checks.add(new Check("never says /" + regex + "/", hit.isEmpty(), "matched \"" + hit.orElse("") + "\""));
        }
        if (e.finalMustNotMatch() != null) for (String regex : e.finalMustNotMatch()) {
            var matcher = Pattern.compile(regex).matcher(finalReply);
            boolean found = matcher.find();
            checks.add(new Check("final reply avoids /" + regex + "/", !found, found ? "matched \"" + matcher.group() + "\"" : ""));
        }
        if (e.maxReplyChars() != null) {
            int longest = replies.stream().mapToInt(String::length).max().orElse(0);
            checks.add(new Check("replies <= " + e.maxReplyChars() + " chars", longest <= e.maxReplyChars(), "longest " + longest));
        }
        checks.addAll(styleChecks(replies));
        if (Boolean.TRUE.equals(e.grounded()) && judge != null) {
            var verdict = judge(judge, judgeSystem, last);
            checks.add(new Check(JUDGE_CHECK, verdict.grounded(), "problems=" + verdict.problems()));
        }
        return new RunResult(c.id(), c.category(), c.critical(), c.resolvedChannel().name(), run, c.turns(), replies,
                last.handoff(), lead, sourceUrls, checks, null);
    }

    /**
     * LLM-as-judge on the final reply, given the exact chunks the bot saw. The judge must back every claim it
     * accepts with a verbatim quote, and code verifies the quote exists: a holistic yes/no from the judge proved
     * unreliable in both directions (flagged facts that were present, missed a misleading brand-to-use link).
     */
    private Verdict judge(ChatClient judge, String judgeSystem, ChatService.Reply reply) {
        var knowledge = new StringBuilder();
        int n = 0;
        for (var source : reply.sources()) {
            var text = jdbc.sql("SELECT text FROM knowledge_chunk WHERE id::text = :id")
                    .param("id", source.chunkId()).query(String.class).optional();
            if (text.isPresent()) knowledge.append("[").append(++n).append("]\n").append(text.get()).append("\n\n");
        }
        var converter = new BeanOutputConverter<>(JudgeOutput.class);
        String output = judge.prompt().system(judgeSystem)
                .options(ChatOptions.builder().model(judgeModel).temperature(0.0))
                .user("KNOWLEDGE:\n" + (knowledge.isEmpty() ? "(none)" : knowledge) + "\nREPLY:\n" + reply.reply()
                        + "\n\n" + converter.getFormat())
                .call().content();
        JudgeOutput parsed;
        try {
            parsed = converter.convert(output);
        } catch (RuntimeException ex) {
            return new Verdict(false, List.of("judge output unparseable"));
        }
        if (parsed == null || parsed.claims() == null) return new Verdict(false, List.of("judge returned no claims"));
        String haystack = normalize(knowledge.toString());
        List<String> problems = new ArrayList<>();
        for (Claim claim : parsed.claims()) {
            // Only positive factual claims are scored; "we don't have that info" / "not something we offer" are allowed.
            if (!"FACT".equalsIgnoreCase(claim.type())) continue;
            if (!claim.supported()) {
                problems.add("unsupported: " + claim.claim());
                continue;
            }
            var spans = claim.evidence() == null ? List.<String>of() : claim.evidence();
            var missing = spans.stream().filter(e -> normalize(e).isBlank() || !haystack.contains(normalize(e))).toList();
            if (spans.isEmpty() || !missing.isEmpty())
                problems.add("evidence not found in knowledge: " + claim.claim() + " <- " + (spans.isEmpty() ? "(none)" : missing));
        }
        return new Verdict(problems.isEmpty(), problems);
    }

    /** Case, punctuation, dashes and whitespace differences must not decide whether a quote "exists". */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static List<Check> styleChecks(List<String> replies) {
        int longest = 0, mostQuestions = 0;
        String longestReply = "", questionReply = "";
        for (String reply : replies) {
            int words = reply.isBlank() ? 0 : reply.strip().split("\\s+").length;
            int questions = (int) reply.chars().filter(ch -> ch == '?').count();
            if (words > longest) { longest = words; longestReply = reply; }
            if (questions > mostQuestions) { mostQuestions = questions; questionReply = reply; }
        }
        return List.of(
                new Check(STYLE_PREFIX + "<= " + MAX_WORDS + " words", longest <= MAX_WORDS,
                        longest + " words: \"" + longestReply + "\""),
                new Check(STYLE_PREFIX + "<= " + MAX_QUESTIONS + " question", mostQuestions <= MAX_QUESTIONS,
                        mostQuestions + " questions: \"" + questionReply + "\""));
    }

    private static String leadField(Lead lead, String field) {
        return switch (field) {
            case "name" -> lead.name();
            case "phone" -> lead.phone();
            case "email" -> lead.email();
            case "city" -> lead.city();
            case "projectType" -> lead.projectType();
            case "spaces" -> lead.spaces();
            case "finishInterest" -> lead.finishInterest();
            case "areaSize" -> lead.areaSize();
            case "timeline" -> lead.timeline();
            case "budget" -> lead.budget();
            case "callbackTime" -> lead.callbackTime();
            default -> throw new IllegalArgumentException("Unknown lead field in cases.json: " + field);
        };
    }

    private Map<String, Object> meta(int repeat, boolean judged, double minPassRate, double passRate,
                                     boolean gatePassed, List<String> criticalFailures) throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(systemPrompt.content().getBytes(StandardCharsets.UTF_8)));
        var datasets = jdbc.sql("SELECT dataset_id || '@' || dataset_version FROM ingestion_run WHERE active")
                .query(String.class).list();
        var meta = new LinkedHashMap<String, Object>();
        meta.put("timestamp", Instant.now().toString());
        meta.put("gate", gatePassed ? "PASSED" : "FAILED");
        meta.put("checkPassRate", passRate);
        meta.put("minPassRate", minPassRate);
        meta.put("criticalFailures", criticalFailures);
        meta.put("model", model);
        meta.put("prompt", promptLabel);
        meta.put("systemPromptSha256", hash.substring(0, 12));
        meta.put("datasets", datasets);
        meta.put("retrieval", Map.of("topK", retrieval.topK(), "candidates", retrieval.candidates(),
                "minSimilarity", retrieval.minSimilarity()));
        meta.put("repeat", repeat);
        meta.put("judge", judged ? judgeModel : "off");
        return meta;
    }

    private Path writeReports(Map<String, Object> meta, List<RunResult> results) throws Exception {
        Path dir = Path.of("target", "evals");
        Files.createDirectories(dir);
        var full = new LinkedHashMap<>(meta);
        full.put("results", results);
        String jsonReport = json.writerWithDefaultPrettyPrinter().writeValueAsString(full);
        String markdown = markdown(meta, results);
        String stamp = meta.get("timestamp").toString().replace(":", "-");
        Files.writeString(dir.resolve("eval-" + stamp + ".json"), jsonReport);
        Files.writeString(dir.resolve("eval-" + stamp + ".md"), markdown);
        Files.writeString(dir.resolve("latest.json"), jsonReport);
        Files.writeString(dir.resolve("latest.md"), markdown);
        return dir.resolve("latest.md").toAbsolutePath();
    }

    /** REVIEW = behaviour checks passed; only the LLM judge or a style check objected: look at it, not a hard failure. */
    private static String label(RunResult r) {
        if (r.passed()) return "✅ PASS";
        return r.deterministicPassed() ? "⚠️ REVIEW" : "❌ FAIL";
    }

    private static Map<String, long[]> byCategory(List<RunResult> results) {
        var map = new TreeMap<String, long[]>();
        for (var r : results) {
            long[] counts = map.computeIfAbsent(r.category(), k -> new long[2]);
            counts[0] += r.passed() ? 1 : 0;
            counts[1]++;
        }
        return map;
    }

    private static String markdown(Map<String, Object> meta, List<RunResult> results) {
        var md = new StringBuilder("# ColourCoats chat eval report\n\n");
        md.append("**Gate: ").append(meta.get("gate")).append("**  \n");
        md.append(String.format("Check pass rate: %.1f%% (gate %.0f%%)  %n", (double) meta.get("checkPassRate") * 100,
                (double) meta.get("minPassRate") * 100));
        md.append("Critical failures: ").append(meta.get("criticalFailures")).append("  \n");
        md.append("Model `").append(meta.get("model")).append("` · prompt ").append(meta.get("prompt"))
                .append(" `").append(meta.get("systemPromptSha256"))
                .append("` · data ").append(meta.get("datasets")).append(" · retrieval ").append(meta.get("retrieval"))
                .append(" · repeat ").append(meta.get("repeat")).append(" · judge ").append(meta.get("judge"))
                .append(" · ").append(meta.get("timestamp")).append("\n\n");

        md.append("## By category\n\n| Category | Passed |\n|---|---|\n");
        byCategory(results).forEach((cat, n) -> md.append("| ").append(cat).append(" | ").append(n[0]).append("/").append(n[1]).append(" |\n"));

        md.append("\n## Cases\n\n| Result | Case | Category | Channel | Critical | Checks |\n|---|---|---|---|---|---|\n");
        for (var r : results) {
            long ok = r.checks().stream().filter(Check::passed).count();
            md.append("| ").append(label(r)).append(" | ").append(r.caseId())
                    .append(r.run() > 1 || results.stream().anyMatch(x -> x.run() > 1) ? " #" + r.run() : "")
                    .append(" | ").append(r.category()).append(" | ").append(r.channel())
                    .append(" | ").append(r.critical() ? "yes" : "").append(" | ").append(ok).append("/").append(r.checks().size()).append(" |\n");
        }

        var failed = results.stream().filter(r -> !r.passed()).toList();
        if (!failed.isEmpty()) md.append("\n## Failures\n");
        for (var r : failed) {
            md.append("\n### ").append(r.caseId()).append(" #").append(r.run()).append(r.critical() ? " (critical)" : "").append("\n\n");
            if (r.error() != null) md.append("- **error:** ").append(r.error()).append("\n");
            r.checks().stream().filter(ch -> !ch.passed())
                    .forEach(ch -> md.append("- **").append(ch.name().replace("|", "\\|")).append("**: ")
                            .append(ch.detail().replace("|", "\\|")).append("\n"));
            md.append("\nTranscript:\n\n");
            for (int i = 0; i < r.turns().size(); i++) {
                md.append("> **Visitor:** ").append(r.turns().get(i)).append("\n>\n");
                if (i < r.replies().size()) md.append("> **Bot:** ").append(r.replies().get(i).replace("\n", "\n> ")).append("\n>\n");
            }
            md.append("\nLead: ").append(r.lead()).append("\n");
        }
        return md.toString();
    }

    private static String summary(Map<String, Object> meta, List<RunResult> results, Path report) {
        var out = new StringBuilder("\n================ ColourCoats chat evals ================\n");
        for (var r : results) {
            out.append(String.format("%s %-9s %-40s %-18s %s%n", label(r).replaceAll("[^A-Z ]", "").strip(),
                    r.critical() ? "critical" : "", r.caseId() + (r.run() > 1 ? " #" + r.run() : ""), r.category(), r.channel()));
            if (r.error() != null) out.append("      x error -> ").append(r.error()).append("\n");
            r.checks().stream().filter(ch -> !ch.passed())
                    .forEach(ch -> out.append("      x ").append(ch.name()).append(" -> ").append(ch.detail()).append("\n"));
        }
        out.append("--------------------------------------------------------\n");
        byCategory(results).forEach((cat, n) -> out.append(String.format("%-20s %d/%d%n", cat, n[0], n[1])));
        out.append(String.format("cases passed: %d/%d   check pass rate: %.1f%% (gate %.0f%%)%n",
                results.stream().filter(RunResult::passed).count(), results.size(),
                (double) meta.get("checkPassRate") * 100, (double) meta.get("minPassRate") * 100));
        out.append("critical failures: ").append(meta.get("criticalFailures")).append("\n");
        out.append("EVAL GATE: ").append(meta.get("gate")).append("\n");
        out.append("report: ").append(report).append("\n");
        return out.append("========================================================\n").toString();
    }
}
