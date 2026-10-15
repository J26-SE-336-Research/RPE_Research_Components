package dev.rpe.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Differential test against the Go reference: replays the mutation corpus written by
 * {@code conformance-kit/go/cmd/difffuzz} and requires the same verdict (canonical encoding, or error code and rule)
 * for every input. Skipped when the corpus has not been generated (scripts/test-unit.sh generates it).
 */
class DifferentialTest {
    @Test
    void javaAgreesWithGoOnEveryMutatedHeader() throws Exception {
        Path corpus = Path.of(System.getProperty("rpe.conformance.dir", "../../conformance-kit/vectors")).resolve("../generated/differential.json");
        Assumptions.assumeTrue(Files.exists(corpus), "differential corpus not generated");
        JsonNode entries = new ObjectMapper().readTree(Files.readString(corpus));
        List<String> diverged = new ArrayList<>();
        for (JsonNode e : entries) {
            String input = e.get("input").asText();
            String canonical = "", code = "", rule = "";
            try {
                canonical = switch (e.get("header").asText()) {
                    case "context" -> RpeContext.decode(input).encode();
                    case "result" -> RpeResult.decode(input).encode();
                    default -> Hop.decode(input).encode();
                };
            } catch (ProtocolException ex) {
                code = ex.code().name();
                rule = ex.rule() == null ? "" : ex.rule();
            } catch (RuntimeException ex) {
                code = "UNEXPECTED " + ex;
            }
            String want = e.path("canonical").asText("") + "|" + e.path("code").asText("") + "|" + e.path("rule").asText("");
            String got = canonical + "|" + code + "|" + rule;
            if (!want.equals(got)) diverged.add(e.get("header").asText() + " " + input + "\n    go:   " + want + "\n    java: " + got);
        }
        assertTrue(entries.size() >= 1000, "corpus too small");
        assertTrue(diverged.isEmpty(), diverged.size() + " divergences, first:\n" + String.join("\n", diverged.subList(0, Math.min(5, diverged.size()))));
    }
}
