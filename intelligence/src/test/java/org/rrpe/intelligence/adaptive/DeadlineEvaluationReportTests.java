package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Runs the guide examples and the built-in scenarios with v2 and, when {@code -Devaluation.out=<file>} is set, writes the
 * markdown table of intelligence/evaluation/v2-results.md (same format and verdicts as the old-logic baseline).
 */
class DeadlineEvaluationReportTests {
    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v); }
    static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", 100 * v); }

    /** One table row; the verdict rules are the ones used for the old-logic baseline. */
    static String row(DeadlineExamples.Example e, ChainSimulation.ChainResult r) {
        var phases = e.script().phases();
        double healthyExceed = 0, total = 0;
        for (var path : phases.get(0).paths()) { healthyExceed += path.share() * DeadlineExamplesTests.exceed(path.profile(), r.finalMs()); total += path.share(); }
        healthyExceed /= total;
        String overload = "n/a", verdict;
        switch (e.scenario()) {
            case "deadline-too-long-under-overload" -> {
                long atEnd = r.phaseEndMs().get(1); double fired = DeadlineExamplesTests.exceed(phases.get(1).paths().get(0).profile(), atEnd);
                overload = pct(fired) + " (D=" + atEnd + " at end of overload)";
                boolean ok = fired >= .05 && healthyExceed <= .01 && !r.phaseIncreased().get(1);
                verdict = (ok ? "**yes**" : "**no**") + ": overload " + (fired >= .05 ? "fired" : "never fired") + "; healthy exceedance " + pct(healthyExceed);
            }
            case "deadline-too-short-day-one" -> verdict = (healthyExceed <= .01 ? "**yes**" : "**no**") + ": healthy exceedance " + pct(healthyExceed);
            default -> {
                boolean changed = !r.phaseEndMs().get(1).equals(r.phaseStartMs().get(1));
                verdict = changed ? "**no**: deadline changed " + r.phaseStartMs().get(1) + " → " + r.phaseEndMs().get(1) + " during the problem phase"
                        : "**yes**: unchanged during the problem phase";
            }
        }
        var steps = r.steps().stream().map(s -> s.fromMs() + " → " + s.toMs() + " (" + s.phase() + ", " + s.decisionCase() + ")").toList();
        var last = r.lastEvidence();
        String finalState = last == null ? "" : " — last: " + last.decisionCase() + (last.holdReason() == null ? "" : " " + last.holdReason()) + ", " + last.state();
        return "| **" + e.title() + "** (start " + e.script().initialMs() + " ms) | " + (steps.isEmpty() ? "none" : String.join("; ", steps)) + " | "
                + r.finalMs() + " ms" + finalState + " | " + pct(healthyExceed) + " | " + overload + " | " + e.expectation() + " | " + verdict + " |";
    }

    @Test void v2GetsEveryExampleRight() throws Exception {
        var md = new StringBuilder("| Scenario | Proposals (applied in order) | Final deadline | Healthy exceedance at final D | Overload exceedance at final D | Correct outcome | Converged / correct? |\n|---|---|---|---|---|---|---|\n");
        int correct = 0;
        for (var e : DeadlineExamples.all()) {
            String row = row(e, DeadlineExamplesTests.simulate(e).result());
            if (row.endsWith("**yes**" + row.substring(row.lastIndexOf("**yes**") + 7))) correct++;
            md.append(row).append('\n');
        }
        assertEquals(6, correct, md.toString());
        md.append("\n### Built-in scenarios (`MockScenarios`, production settings)\n\n| Scenario | Changes returned |\n|---|---|\n");
        var scenarios = new MockScenarios(AdaptiveConfig.Settings.defaults(), AdaptiveEngineTests.connections(""));
        for (String name : List.of("deadline-increase", "deadline-recovery", "overloaded", "stressed", "retry-stressed", "recovery", "healthy", "multiple-chains")) {
            @SuppressWarnings("unchecked") var changes = (List<PolicyChange>) scenarios.run(name).get("changes");
            var text = new StringBuilder();
            for (var c : changes) text.append(c.proposal().action()).append(' ').append(c.proposal().target().split(" \\| ")[0]).append(": ")
                    .append(fmt(c.proposal().currentValue())).append(" → ").append(fmt(c.proposal().proposedValue())).append(" (").append(c.decision()).append("); ");
            md.append("| `").append(name).append("` | ").append(text.length() == 0 ? "none" : text).append(" |\n");
        }
        String out = System.getProperty("evaluation.out");
        if (out != null) Files.writeString(Path.of(out), md.toString());
    }
}
