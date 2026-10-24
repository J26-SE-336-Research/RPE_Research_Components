package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Practice cards (POST /api/v2/intelligence/demo/{scenario}?to=queue): a simulation's recommendations copied into the live
 * recommendation list as synthetic (MOCK) cards that wait for a reviewer. They are reviewed with the normal review (one
 * decision for all parts of a joint card) and are never delivered to Component 3: {@link PolicyWorkflow#deliverable} never
 * returns a MOCK card. MOCK targets are keyed apart from live ones, so practice cards never affect live decisions. They stay
 * for {@link #LIFETIME} or until cleared; at most {@link #MAX_CARDS} are kept (the oldest go first), so they cannot push real
 * cards out of the status list.
 */
final class PracticeCards {
    private PracticeCards() {}
    static final Duration LIFETIME = Duration.ofHours(1);
    static final int MAX_CARDS = 30;
    private static int versions;

    /** One practice card before it is offered; {@code group} links the parts of one joint card. */
    private record Card(String target, Action action, double current, double proposed, String unit, Map<String, Double> measurements, List<String> evidence, String group) {}

    /** Adds the scenario's recommendations as practice cards; returns what was added (id, setting, target, values). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> add(String scenario, Map<String, Object> result, PolicyWorkflow workflow, Instant now) {
        var cards = new ArrayList<Card>();
        String note = "Simulation: practice card from the scenario " + scenario + ". It can be approved or rejected; it is never applied.";
        if (result.get("rateEvidence") instanceof List<?> rates && !rates.isEmpty()) {
            // a rate scenario: the first recommendation of each setting after the learning phase
            var evidence = (Map<String, Object>) rates.get(0);
            String service = String.valueOf(evidence.get("service"));
            var seen = new HashSet<Action>();
            for (var s : (List<ServiceSimulation.Step>) evidence.get("steps")) {
                if (s.phase().startsWith("healthy (learn)") || !seen.add(s.action())) continue;
                cards.add(new Card(service, s.action(), s.from(), s.to(), s.action() == Action.LEAKY_BUCKET ? "requests/second" : "extra attempts/original request",
                        Map.of(), List.of(s.evidence(), "Phase: " + s.phase(), note), null));
            }
        } else {
            var changes = (List<PolicyChange>) result.getOrDefault("changes", List.of());
            var jointOf = new HashMap<String, String>();
            if (result.get("joints") instanceof List<?> joints) for (var j : joints) {
                var joint = (Map<String, Object>) j;
                for (var p : (List<Map<String, Object>>) joint.get("parts")) jointOf.put(String.valueOf(p.get("proposalId")), String.valueOf(joint.get("jointId")));
            }
            boolean simulatedReviewer = Boolean.TRUE.equals(result.get("simulatedApprovals"));
            var picked = new LinkedHashMap<String, PolicyChange>();
            if (simulatedReviewer) {
                // the steps were approved by a simulated reviewer: the first recommendation of each target and setting
                changes.stream().sorted(Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt()))
                        .forEach(c -> picked.putIfAbsent(c.proposal().target() + "|" + c.proposal().action(), c));
            } else {
                for (var c : changes) if (c.decision() == Decision.PENDING_APPROVAL && c.delivery() != Delivery.SUPERSEDED && c.delivery() != Delivery.EXPIRED)
                    picked.put(c.proposal().id().toString(), c);
            }
            for (var c : picked.values()) {
                var p = c.proposal(); var evidence = new ArrayList<>(p.evidence()); evidence.add(note);
                var measurements = new HashMap<>(p.measurements()); measurements.remove("windowEndEpochSeconds");
                cards.add(new Card(p.target(), p.action(), p.currentValue(), p.proposedValue(), p.unit(), measurements, evidence, jointOf.get(p.id().toString())));
            }
        }
        if (cards.isEmpty()) return List.of();
        // one MOCK policy reference per target with the current values of its cards
        String version = "practice-" + now.getEpochSecond() + "-" + (++versions);
        Instant until = now.plus(LIFETIME);
        var byTarget = new LinkedHashMap<String, List<Card>>();
        cards.forEach(c -> byTarget.computeIfAbsent(c.target(), t -> new ArrayList<>()).add(c));
        byTarget.forEach((target, list) -> {
            Double rate = null, retry = null, deadline = null;
            for (var c : list) switch (c.action()) {
                case LEAKY_BUCKET -> rate = c.current();
                case RETRY_BUDGET -> retry = c.current();
                case CHAIN_DEADLINE -> deadline = c.current();
            }
            workflow.putPolicy(new Policy(target, version, until, rate, rate, retry, deadline), Source.MOCK);
        });
        var added = new ArrayList<PolicyChange>();
        var groups = new LinkedHashMap<String, List<Proposal>>();
        for (var c : cards) {
            var measurements = new HashMap<>(c.measurements());
            measurements.put("windowEndEpochSeconds", (double) until.getEpochSecond()); // stays current for its lifetime
            var p = new Proposal(UUID.randomUUID(), c.target(), c.action(), Source.MOCK, version, c.current(), c.proposed(), c.unit(), now, until,
                    Map.copyOf(measurements), List.copyOf(c.evidence()));
            if (c.group() == null) added.add(workflow.offer(p)); else groups.computeIfAbsent(c.group(), g -> new ArrayList<>()).add(p);
        }
        for (var g : groups.values()) {
            if (g.size() > 1) added.addAll(workflow.offerJoint(g)); else added.add(workflow.offer(g.get(0)));
        }
        workflow.removeSynthetic(MAX_CARDS);
        return added.stream().map(c -> Map.<String, Object>of("proposalId", c.proposal().id().toString(), "action", c.proposal().action().name(),
                "target", c.proposal().target(), "currentValue", c.proposal().currentValue(), "proposedValue", c.proposal().proposedValue())).toList();
    }

    /** Removes every practice card (and the practice policy references); live cards and the audit trail are untouched. */
    static int clear(PolicyWorkflow workflow) { return workflow.removeSynthetic(0); }
}
