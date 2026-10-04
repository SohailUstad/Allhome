package com.allhome.colourcoats.eval;

import com.allhome.colourcoats.chat.SystemPrompts;
import com.allhome.colourcoats.livemodel.LiveModelService;
import com.allhome.colourcoats.prompt.PromptService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-line evals: the same runner and cases as the console's Evals page (cases from the database, loaded from
 * evals/cases.json on first start), against the real pipeline: pgvector retrieval + OpenAI (costs money).
 *
 * Run:  ./mvnw test -Pevals   (options: -Deval.repeat=3 -Deval.minPassRate=0.85 -Deval.judge=false -Deval.case=<id>
 *                              -Deval.prompt=active|file|<version id> -Deval.model=<model>)
 * Gate: every run of every critical case must pass its behaviour checks, and the check pass rate must reach minPassRate.
 * Report: target/evals/latest.md (human) and latest.json (diffable), plus timestamped copies.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatEvalTests {
    @Autowired EvalRunner runner;
    @Autowired EvalService evals;
    @Autowired PromptService prompts;
    @Autowired LiveModelService liveModels;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Value("classpath:prompts/sales-system.md") Resource promptFile;
    String judgeModel = "gpt-4.1";

    record RunResult(int run, CaseResult result) {}

    @Test
    void chatBehaviourMeetsEvalGate() throws Exception {
        int repeat = Integer.getInteger("eval.repeat", 1);
        double minPassRate = Double.parseDouble(System.getProperty("eval.minPassRate", "0.85"));
        boolean useJudge = !"false".equals(System.getProperty("eval.judge"));
        String only = System.getProperty("eval.case");
        String model = System.getProperty("eval.model", liveModels.current().model());
        var prompt = selectPrompt(System.getProperty("eval.prompt", "active"));
        evals.seedCases();
        List<EvalCase> cases = evals.enabledCases();
        if (only != null && !only.isBlank()) cases = cases.stream().filter(c -> c.id().equals(only)).toList();

        List<RunResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            for (int run = 1; run <= repeat; run++) {
                System.out.printf("running %-40s #%d%n", c.id(), run);
                results.add(new RunResult(run, runner.run(c, prompt.prompt(), model, useJudge)));
            }
        }

        long checks = results.stream().mapToLong(r -> r.result().checks().size() + (r.result().error() == null ? 0 : 1)).sum();
        long passedChecks = results.stream().flatMap(r -> r.result().checks().stream()).filter(CaseResult.Check::passed).count();
        double passRate = checks == 0 ? 1 : (double) passedChecks / checks;
        var criticalFailures = results.stream().filter(r -> r.result().evalCase().critical() && !r.result().behaviourPassed())
                .map(r -> r.result().evalCase().id()).distinct().toList();
        boolean gatePassed = criticalFailures.isEmpty() && passRate >= minPassRate;

        var meta = new LinkedHashMap<String, Object>();
        meta.put("timestamp", Instant.now().toString());
        meta.put("gate", gatePassed ? "PASSED" : "FAILED");
        meta.put("checkPassRate", passRate);
        meta.put("minPassRate", minPassRate);
        meta.put("criticalFailures", criticalFailures);
        meta.put("model", model);
        meta.put("prompt", prompt.label());
        meta.put("systemPromptSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.prompt().content().getBytes(StandardCharsets.UTF_8))).substring(0, 12));
        meta.put("datasets", jdbc.sql("SELECT dataset_id || '@' || dataset_version FROM ingestion_run WHERE active").query(String.class).list());
        meta.put("repeat", repeat);
        meta.put("judge", useJudge ? judgeModel : "off");
        Path report = writeReports(meta, results);
        System.out.println(summary(meta, results, report));

        assertThat(criticalFailures).as("critical eval cases failed, see %s", report).isEmpty();
        assertThat(passRate).as("check pass rate below gate, see %s", report).isGreaterThanOrEqualTo(minPassRate);
    }

    record Selected(SystemPrompts.SystemPrompt prompt, String label) {}

    /** "active" (what visitors get now), "file" (the git file) or a version id (e.g. a draft before activating it). */
    Selected selectPrompt(String choice) throws Exception {
        return switch (choice) {
            case "active" -> {
                var p = prompts.active();
                yield new Selected(p, "v" + p.versionNumber() + " (active)");
            }
            case "file" -> new Selected(new SystemPrompts.SystemPrompt(null, null,
                    promptFile.getContentAsString(StandardCharsets.UTF_8)), "file");
            default -> {
                var v = prompts.version(UUID.fromString(choice));
                yield new Selected(new SystemPrompts.SystemPrompt(v.getId(), v.getVersionNumber(), v.getContent()),
                        "v" + v.getVersionNumber() + " (" + v.getStatus().name().toLowerCase() + ")");
            }
        };
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

    private static Map<String, long[]> byCategory(List<RunResult> results) {
        var map = new TreeMap<String, long[]>();
        for (var r : results) {
            long[] counts = map.computeIfAbsent(r.result().evalCase().category(), k -> new long[2]);
            counts[0] += r.result().passed() ? 1 : 0;
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
                .append(" `").append(meta.get("systemPromptSha256")).append("` · data ").append(meta.get("datasets"))
                .append(" · repeat ").append(meta.get("repeat")).append(" · judge ").append(meta.get("judge"))
                .append(" · ").append(meta.get("timestamp")).append("\n\n");
        md.append("## By category\n\n| Category | Passed |\n|---|---|\n");
        byCategory(results).forEach((cat, n) -> md.append("| ").append(cat).append(" | ").append(n[0]).append("/").append(n[1]).append(" |\n"));
        md.append("\n## Cases\n\n| Result | Case | Category | Channel | Critical | Checks |\n|---|---|---|---|---|---|\n");
        for (var r : results) {
            var c = r.result();
            long ok = c.checks().stream().filter(CaseResult.Check::passed).count();
            md.append("| ").append(c.label()).append(" | ").append(c.evalCase().id()).append(r.run() > 1 ? " #" + r.run() : "")
                    .append(" | ").append(c.evalCase().category()).append(" | ").append(c.evalCase().resolvedChannel())
                    .append(" | ").append(c.evalCase().critical() ? "yes" : "").append(" | ").append(ok).append("/")
                    .append(c.checks().size()).append(" |\n");
        }
        var failed = results.stream().filter(r -> !r.result().passed()).toList();
        if (!failed.isEmpty()) md.append("\n## Failures\n");
        for (var r : failed) {
            var c = r.result();
            md.append("\n### ").append(c.evalCase().id()).append(" #").append(r.run()).append(c.evalCase().critical() ? " (critical)" : "").append("\n\n");
            if (c.error() != null) md.append("- **error:** ").append(c.error()).append("\n");
            c.checks().stream().filter(ch -> !ch.passed())
                    .forEach(ch -> md.append("- **").append(ch.name().replace("|", "\\|")).append("**: ")
                            .append(ch.detail().replace("|", "\\|")).append("\n"));
            md.append("\nTranscript:\n\n");
            var turns = c.evalCase().turns();
            for (int i = 0; i < turns.size(); i++) {
                md.append("> **Visitor:** ").append(turns.get(i)).append("\n>\n");
                if (i < c.replies().size()) md.append("> **Bot:** ").append(c.replies().get(i).replace("\n", "\n> ")).append("\n>\n");
            }
            md.append("\nLead: ").append(c.lead()).append("\n");
        }
        return md.toString();
    }

    private static String summary(Map<String, Object> meta, List<RunResult> results, Path report) {
        var out = new StringBuilder("\n================ ColourCoats chat evals ================\n");
        for (var r : results) {
            var c = r.result();
            out.append(String.format("%-6s %-9s %-40s %-18s %s%n", c.label(), c.evalCase().critical() ? "critical" : "",
                    c.evalCase().id() + (r.run() > 1 ? " #" + r.run() : ""), c.evalCase().category(), c.evalCase().resolvedChannel()));
            if (c.error() != null) out.append("      x error -> ").append(c.error()).append("\n");
            c.checks().stream().filter(ch -> !ch.passed())
                    .forEach(ch -> out.append("      x ").append(ch.name()).append(" -> ").append(ch.detail()).append("\n"));
        }
        out.append("--------------------------------------------------------\n");
        byCategory(results).forEach((cat, n) -> out.append(String.format("%-20s %d/%d%n", cat, n[0], n[1])));
        out.append(String.format("cases passed: %d/%d   check pass rate: %.1f%% (gate %.0f%%)%n",
                results.stream().filter(r -> r.result().passed()).count(), results.size(),
                (double) meta.get("checkPassRate") * 100, (double) meta.get("minPassRate") * 100));
        out.append("critical failures: ").append(meta.get("criticalFailures")).append("\n");
        out.append("EVAL GATE: ").append(meta.get("gate")).append("\n");
        out.append("report: ").append(report).append("\n");
        return out.append("========================================================\n").toString();
    }
}
