package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.rrpe.intelligence.adaptive.ChainSimulation.*;
import java.util.*;

/**
 * The worked examples of the v2 guide (§7) as deterministic traffic scripts. The same traffic was used for the old-logic
 * baseline (intelligence/evaluation/v1-baseline-results.md), so the results compare like for like. Every example starts
 * with {@link #LEARN} cycles of healthy traffic.
 */
public final class DeadlineExamples {
    private DeadlineExamples() {}
    public static final int LEARN = 16;
    public record Example(String scenario, String title, Script script, String expectation) {}

    static final Profile H400 = profile(150, 350, 380, 400, 420), O900 = profile(800, 880, 895, 900, 905);
    static final Profile H1000 = profile(500, 950, 980, 1000, 1020), SLOW = profile(700, 1190, 1230, 1260, 1290);
    static final Profile DAY1 = new Profile(List.of(new Segment(.75, 150, 295), new Segment(.22, 310, 445)), new double[]{480, 500, 520});
    static final Profile H600 = profile(300, 560, 580, 600, 620), QUEUED = profile(900, 1400, 1450, 1500, 1550), SHED = profile(4, 6, 5);
    static final Profile CACHE = profile(50, 140, 145, 150, 155), DB = profile(300, 580, 590, 600, 610), DB_SLOW = profile(400, 780, 790, 800, 810);
    static final String GW = "gateway";

    private static PathTraffic path(double share, Profile profile, String... services) { return new PathTraffic(share, profile, services); }
    private static Phase healthy(int cycles, List<PathTraffic> paths) { return new Phase("healthy (learn)", cycles, paths); }

    public static List<Example> all() {
        return List.of(
            new Example("deadline-too-long-under-overload", "Example 1: deadline too long under overload", new Script("GET /api/orders", 2000, List.of(
                    healthy(LEARN, List.of(path(1, H400, GW, "order", "payment"))),
                    new Phase("payment overloaded (~900 ms)", 120, List.of(path(1, O900, GW, "order", "payment")), Map.of("payment", Health.OVERLOADED), Map.of()),
                    new Phase("recovered", 120, List.of(path(1, H400, GW, "order", "payment"))))),
                    "decreases only, until the overload fires (≥5% expired); after recovery tighten into [D*, 1.25·D*]"),
            new Example("services-slowing", "Example 2: services slowing (inventory STRESSED)", new Script("GET /api/orders", 1250, List.of(
                    healthy(LEARN, List.of(path(.7, H1000, GW, "order", "payment"), path(.3, H1000, GW, "order", "inventory"))),
                    new Phase("inventory stressed, its paths slower", 120, List.of(path(.7, H1000, GW, "order", "payment"), path(.3, SLOW, GW, "order", "inventory")),
                            Map.of("inventory", Health.STRESSED), Map.of()))),
                    "no increase (stress gate); late requests on the stressed path are signalled"),
            new Example("deadline-too-short-day-one", "Example 3: deadline too short from day one", new Script("GET /api/orders", 300, List.of(
                    new Phase("healthy, 25% over 300 ms", 200, List.of(path(1, DAY1, GW, "order"))))),
                    "raise in a few steps until healthy exceedance ≤1% (300 → 450 → 680)"),
            new Example("queue-rate-not-deadline", "Example 4: queue rate too low, not the deadline", new Script("GET /api/stock", 800, List.of(
                    healthy(LEARN, List.of(path(1, H600, GW, "inventory"))),
                    new Phase("inventory queue full (pressure 95%, 503s, queue wait 75%)", 120,
                            List.of(path(.95, QUEUED, GW, "inventory"), new PathTraffic(List.of(GW, "inventory"), .05, SHED, 503, false)),
                            Map.of("inventory", Health.STRESSED),
                            Map.of("inventory", new ServiceLoadSnapshot("inventory", 95.0, null, 6.0, 0.0, .25, null, null, .75, List.of()))))),
                    "no deadline change; advise raising the inventory rate"),
            new Example("mixed-paths", "Example 5: mixed paths (cache 70% / database 30%), database STRESSED", new Script("GET /api/catalog", 700, List.of(
                    healthy(LEARN, List.of(path(.7, CACHE, GW, "catalog-cache"), path(.3, DB, GW, "catalog-db"))),
                    new Phase("database stressed", 120, List.of(path(.7, CACHE, GW, "catalog-cache"), path(.3, DB_SLOW, GW, "catalog-db")),
                            Map.of("catalog-db", Health.STRESSED), Map.of()))),
                    "no change; the healthy time stays near the mixed p99 (not the cache p99)"),
            new Example("rejecting-middleware-504", "Example 6: an older rejecting middleware cuts requests at the deadline (504)", new Script("GET /api/orders", 1000, List.of(
                    healthy(LEARN, List.of(path(1, H600, GW, "order"))),
                    new Phase("3% cut by 504 at ≈D", 60, List.of(path(.97, H600, GW, "order"), new PathTraffic(List.of(GW, "order"), .03, H600, 504, true))))),
                    "no change; hold and report the cutting middleware"));
    }
    public static Example byScenario(String scenario) {
        return all().stream().filter(e -> e.scenario().equals(scenario)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown scenario"));
    }
}
