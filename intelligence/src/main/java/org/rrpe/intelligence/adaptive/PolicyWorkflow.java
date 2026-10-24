package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Recommendation workflow. Every recommendation waits for an authorised human decision (proposal commitment);
 * approval, transport acceptance by Component 3 and applied enforcement are distinct states. Every step is audited.
 */
@Service
public class PolicyWorkflow {
    private final Clock clock;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    private final Map<String, Policy> policies = new LinkedHashMap<>();
    private final Map<UUID, PolicyChange> history = new LinkedHashMap<>();
    private AuditLog audit = AuditLog.NONE;
    @Autowired(required = false) public void setAudit(AuditLog audit) { this.audit = audit == null ? AuditLog.NONE : audit; }
    private java.time.Duration evidenceDelay = java.time.Duration.ZERO;
    /** Windows are evaluated this long after they end (Tempo ingestion); evidence age allows for it. */
    @Autowired(required = false)
    void setEvidenceDelay(@org.springframework.beans.factory.annotation.Value("${rrpe.adaptive.evaluation-delay:0s}") java.time.Duration delay) {
        if (delay != null && !delay.isNegative()) evidenceDelay = delay;
    }
    @Autowired public PolicyWorkflow(AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) { this(connections, settings, Clock.systemUTC()); }
    public PolicyWorkflow(AdaptiveConfig.Connections connections, Clock clock) { this(connections, AdaptiveConfig.Settings.defaults(), clock); }
    public PolicyWorkflow(AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings, Clock clock) {
        this.connections = connections; this.settings = settings; this.clock = clock;
    }
    private String key(String target, Source source) { return (source == Source.MOCK ? "MOCK:" : "LIVE:") + target; }

    public synchronized void putPolicy(Policy policy, Source source) {
        if (policy == null || policy.target() == null || policy.target().isBlank() || policy.version() == null || policy.version().isBlank()
                || policy.validUntil() == null || !policy.validUntil().isAfter(clock.instant())) throw new IllegalArgumentException("Current policy identity, version and future validity are required");
        for (Double value : new Double[]{policy.bucketRate(), policy.healthyBucketTarget(), policy.retryRatio(), policy.deadlineMs()}) {
            if (value != null && (!Double.isFinite(value) || value < 0)) throw new IllegalArgumentException("Policy values must be finite and nonnegative");
        }
        if (policy.retryRatio() != null && policy.retryRatio() > .10 || policy.bucketRate() != null && policy.bucketRate() == 0
                || policy.deadlineMs() != null && policy.deadlineMs() == 0) throw new IllegalArgumentException("Invalid current policy limits");
        policies.put(key(policy.target(), source), policy);
        for (var item : new ArrayList<>(history.values())) {
            if (key(item.proposal().target(), item.proposal().source()).equals(key(policy.target(), source))
                    && !item.proposal().baseVersion().equals(policy.version()) && active(item)) {
                if (rebase(item, policy)) continue;
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, "Current policy version changed", item.attempts(), item.lastAttempt(), null);
            }
        }
        cascadeJoints();
    }
    public synchronized Policy current(String target, Source source) {
        Policy result = policies.get(key(target, source));
        return result != null && result.validUntil().isAfter(clock.instant()) ? result : null;
    }
    /** A measured startup route budget is a configuration reference, not a fabricated Component 3 approval/version. */
    public synchronized boolean observeConfiguredDeadline(String target, double deadline, Instant observedAt) {
        if (!Double.isFinite(deadline) || deadline <= 0 || deadline != Math.rint(deadline)
                || observedAt == null || observedAt.isAfter(clock.instant())
                || java.time.Duration.between(observedAt, clock.instant()).compareTo(settings.cycle().multipliedBy(2).plus(evidenceDelay)) > 0) return false;
        String version = "prometheus-config:" + Long.toString((long) deadline);
        Policy existing = current(target, Source.TEMPO);
        if (existing != null && !existing.version().startsWith("prometheus-config:")
                && !Objects.equals(existing.deadlineMs(), deadline)) return false;
        if (existing != null && !existing.version().startsWith("prometheus-config:")) version = existing.version();
        putPolicy(new Policy(target, version, observedAt.plus(settings.cycle().multipliedBy(2)).plus(evidenceDelay), null, null, null, deadline), Source.TEMPO);
        return true;
    }
    /**
     * The sidecar's configured inbound rate ({@code rpe_backpressure_inbound_rate_per_second}) as the rate reference, like
     * {@link #observeConfiguredDeadline} for the deadline: with no Component 3 policy the service gets a short-lived reference
     * {@code prometheus-config:rate:<r>}; a Component 3 policy without a rate gets the rate filled in (same version); a Component 3
     * rate always wins. While a change of the service is approved and not yet applied the reference is only kept alive, so the
     * rollout itself (which changes the sidecar's rate) never supersedes that change.
     */
    public synchronized boolean observeConfiguredRate(String service, double rate, Instant observedAt) {
        if (!Double.isFinite(rate) || rate <= 0 || observedAt == null || observedAt.isAfter(clock.instant())
                || java.time.Duration.between(observedAt, clock.instant()).compareTo(settings.cycle().multipliedBy(2).plus(evidenceDelay)) > 0) return false;
        Instant until = observedAt.plus(settings.cycle().multipliedBy(2)).plus(evidenceDelay);
        Policy existing = current(service, Source.PROMETHEUS);
        if (existing != null && !existing.version().startsWith("prometheus-config:")) {
            if (existing.bucketRate() != null) return false; // a Component 3 rate wins
            putPolicy(new Policy(service, existing.version(), existing.validUntil(), rate,
                    existing.healthyBucketTarget() != null ? existing.healthyBucketTarget() : rate, existing.retryRatio(), existing.deadlineMs()), Source.PROMETHEUS);
            return true;
        }
        boolean inFlight = history.values().stream().anyMatch(c -> c.proposal().target().equals(service) && c.proposal().source() != Source.MOCK
                && c.proposal().action() != Action.CHAIN_DEADLINE && c.decision() == Decision.APPROVED && active(c));
        if (existing != null && inFlight) {
            putPolicy(new Policy(service, existing.version(), until, existing.bucketRate(), existing.healthyBucketTarget(), existing.retryRatio(), null), Source.PROMETHEUS);
            return true;
        }
        String version = "prometheus-config:rate:" + (rate == Math.rint(rate) ? String.valueOf((long) rate) : String.valueOf(rate));
        Double healthy = existing != null && existing.healthyBucketTarget() != null ? existing.healthyBucketTarget() : rate;
        putPolicy(new Policy(service, version, until, rate, healthy, existing == null ? null : existing.retryRatio(), null), Source.PROMETHEUS);
        return true;
    }
    /** Withdraws only the cards of {@code target} and {@code action} that still wait for a person (not joint parts, not approved changes). */
    public synchronized void withdrawPending(String target, Action action, Source source, String reason) {
        for (var item : new ArrayList<>(history.values())) {
            if (item.proposal().target().equals(target) && item.proposal().action() == action && item.decision() == Decision.PENDING_APPROVAL
                    && !jointOf.containsKey(item.proposal().id()) && key(target, item.proposal().source()).equals(key(target, source)) && active(item)) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, reason, item.attempts(), item.lastAttempt(), null);
            }
        }
        cascadeJoints();
    }
    public synchronized Instant lastApplied(String target, Action action, Source source) {
        return history.values().stream().filter(item -> item.proposal().target().equals(target) && item.proposal().action() == action
                && key(target, item.proposal().source()).equals(key(target, source)) && item.delivery() == Delivery.APPLIED)
                .map(PolicyChange::lastAttempt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
    public synchronized PolicyChange offer(Proposal proposal) { return offer(proposal, null); }
    /**
     * Automatic approval: with auto (reviewer c4-auto or c4-auto-revert, picked by the guard) a rate or retry change is
     * created as APPROVED and audited as decided. This never happens for a chain deadline or a simulation. Without
     * auto every recommendation waits for a person.
     */
    public synchronized PolicyChange offer(Proposal proposal, Review auto) {
        if (auto != null && (proposal.action() == Action.CHAIN_DEADLINE || proposal.source() == Source.MOCK || auto.reviewer() == null
                || !auto.reviewer().startsWith("c4-auto") || !auto.approve())) throw new IllegalArgumentException("Automatic approval is only for live rate and retry recommendations");
        expire();
        Policy policy = current(proposal.target(), proposal.source());
        if (policy == null || !policy.version().equals(proposal.baseVersion()) || !proposal.expiresAt().isAfter(clock.instant())
                || proposal.generatedAt().isAfter(clock.instant()) || !Double.isFinite(proposal.proposedValue()) || proposal.proposedValue() < 0) {
            throw new IllegalArgumentException("Proposal requires current evidence and the current policy version");
        }
        double actual = value(policy, proposal.action());
        if (Double.compare(actual, proposal.currentValue()) != 0 || proposal.action() == Action.RETRY_BUDGET && proposal.proposedValue() > .10
                || proposal.action() != Action.RETRY_BUDGET && proposal.proposedValue() <= 0) throw new IllegalArgumentException("Proposal current value or limits are inconsistent");
        for (var previous : new ArrayList<>(history.values())) {
            // A human rejection holds for the identical recommendation (same base policy and value) for 30 minutes,
            // so the reviewer is not asked again every cycle.
            boolean heldRejection = previous.decision() == Decision.REJECTED && previous.decidedAt() != null
                    && previous.decidedAt().plus(java.time.Duration.ofMinutes(30)).isAfter(clock.instant());
            if (sameControl(previous.proposal(), proposal) && (active(previous) || heldRejection)) {
                if (previous.proposal().baseVersion().equals(proposal.baseVersion())
                        && Double.compare(previous.proposal().proposedValue(), proposal.proposedValue()) == 0) {
                    // Same recommendation confirmed by a newer window: keep its identity and decision, refresh the evidence.
                    var p = proposal;
                    var refreshed = new Proposal(previous.proposal().id(), p.target(), p.action(), p.source(), p.baseVersion(), p.currentValue(),
                            p.proposedValue(), p.unit(), p.generatedAt(), p.expiresAt(), p.measurements(), p.evidence());
                    PolicyChange updated = new PolicyChange(refreshed, previous.decision(), previous.reviewer(), previous.decidedAt(), previous.delivery(),
                            previous.detail(), previous.attempts(), previous.lastAttempt(), previous.appliedVersion());
                    history.put(refreshed.id(), updated);
                    if (auto != null && updated.decision() == Decision.PENDING_APPROVAL) {
                        updated = replace(updated, Decision.APPROVED, auto.reviewer(), clock.instant(), updated.delivery(), auto.comment(), updated.attempts(), updated.lastAttempt(), null);
                        audit.decided(updated);
                    }
                    return updated;
                }
                replace(previous, previous.decision(), previous.reviewer(), previous.decidedAt(), Delivery.SUPERSEDED,
                        "Superseded by newer evidence", previous.attempts(), previous.lastAttempt(), null);
            }
        }
        cascadeJoints(); // a normal proposal that replaced a joint part withdraws the rest of that joint
        // Every recommendation requires human approval before delivery, unless the engine's guard approved a rate or retry change.
        Delivery delivery = proposal.source() == Source.MOCK || connections.component3Url().isBlank()
                ? Delivery.WAITING_FOR_COMPONENT_3 : Delivery.READY;
        var change = new PolicyChange(proposal, Decision.PENDING_APPROVAL, null, null, delivery,
                proposal.source() == Source.MOCK ? "Synthetic example; never sent to Component 3" : "Awaiting human approval", 0, null, null);
        history.put(proposal.id(), change);
        audit.proposed(change);
        if (auto != null) {
            change = replace(change, Decision.APPROVED, auto.reviewer(), clock.instant(), delivery, auto.comment(), 0, null, null);
            audit.decided(change);
        }
        if (history.size() > 2000) history.entrySet().removeIf(entry -> !active(entry.getValue()) && history.size() > 1000);
        return change;
    }
    public synchronized void withdraw(String target, Action action, Source source, String reason) {
        for (var item : new ArrayList<>(history.values())) {
            if (item.proposal().target().equals(target) && item.proposal().action() == action
                    && key(target, item.proposal().source()).equals(key(target, source)) && active(item)) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, reason,
                        item.attempts(), item.lastAttempt(), null);
            }
        }
        cascadeJoints();
    }
    public synchronized PolicyChange decide(UUID id, Review review) {
        expire(); var item = find(id);
        if (review == null || review.reviewer() == null || review.reviewer().isBlank()) throw new IllegalArgumentException("Reviewer is required");
        if (!active(item)) throw new IllegalArgumentException("This recommendation is no longer current");
        Decision decision = review.approve() ? Decision.APPROVED : Decision.REJECTED;
        if (item.decision() != Decision.PENDING_APPROVAL) {
            if (item.decision() == decision && review.reviewer().equals(item.reviewer())) return item;
            throw new IllegalArgumentException("Recommendation already has a decision");
        }
        UUID joint = jointOf.get(id);
        if (joint != null) {
            // A joint recommendation is one decision: every part gets the same review.
            var parts = joints.get(joint).stream().map(history::get).filter(Objects::nonNull).toList();
            if (parts.stream().anyMatch(c -> !active(c) || c.decision() != Decision.PENDING_APPROVAL)) throw new IllegalArgumentException("This joint recommendation is no longer current");
            for (var c : parts) audit.decided(replace(c, decision, review.reviewer(), clock.instant(), c.delivery(), review.comment(), c.attempts(), c.lastAttempt(), null));
            return history.get(id);
        }
        var decided = replace(item, decision, review.reviewer(), clock.instant(), item.delivery(), review.comment(), item.attempts(), item.lastAttempt(), null);
        audit.decided(decided);
        return decided;
    }
    public synchronized List<PolicyChange> list() { expire(); return history.values().stream().sorted(java.util.Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt()).reversed()).limit(100).toList(); }
    /** Changes accepted by Component 3 that are still waiting for enforcement confirmation. */
    public synchronized List<PolicyChange> sent() {
        expire(); return history.values().stream().filter(this::active).filter(item -> item.delivery() == Delivery.SENT).toList();
    }
    public synchronized List<PolicyChange> deliverable() {
        expire(); return history.values().stream().filter(this::active)
                .filter(item -> item.proposal().source() != Source.MOCK && item.decision() == Decision.APPROVED)
                .filter(item -> item.delivery() != Delivery.SENT && item.attempts() < 5
                        && (item.lastAttempt() == null || clock.instant().isAfter(item.lastAttempt().plusSeconds(30))))
                .limit(100).toList();
    }
    /** Withdraws only active deadline proposals that would raise the deadline above {@code currentValue} (Case C). */
    public synchronized void withdrawPendingIncrease(String target, Source source, double currentValue, String reason) {
        for (var item : new ArrayList<>(history.values())) {
            if (item.proposal().target().equals(target) && item.proposal().action() == Action.CHAIN_DEADLINE
                    && key(target, item.proposal().source()).equals(key(target, source)) && active(item) && item.proposal().proposedValue() > currentValue) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, reason, item.attempts(), item.lastAttempt(), null);
            }
        }
        cascadeJoints();
    }
    /** The newest active (not yet applied, expired, superseded or rejected) recommendation for a target and action. */
    public synchronized java.util.Optional<PolicyChange> pending(String target, Action action, Source source) {
        expire();
        return history.values().stream().filter(this::active)
                .filter(item -> item.proposal().target().equals(target) && item.proposal().action() == action
                        && key(target, item.proposal().source()).equals(key(target, source)))
                .max(java.util.Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt()));
    }
    public synchronized PolicyChange find(UUID id) {
        var item = history.get(id); if (item == null) throw new IllegalArgumentException("Unknown proposal"); return item;
    }
    public synchronized void deliveryResult(UUID id, Acknowledgement ack, String failure) {
        expire(); var item = find(id);
        if (!active(item) || item.decision() != Decision.APPROVED) return;
        Instant now = clock.instant();
        if (failure != null) {
            audit.delivery(replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.FAILED, failure, item.attempts() + 1, now, null)); return;
        }
        if (ack == null || !("ACCEPTED".equals(ack.status()) || "APPLIED".equals(ack.status()))) throw new IllegalArgumentException("Expected ACCEPTED or APPLIED acknowledgement");
        if ("APPLIED".equals(ack.status())) {
            if (ack.policyVersion() == null || ack.policyVersion().isBlank() || ack.policyVersion().equals(item.proposal().baseVersion())
                    || ack.appliedValue() == null || Double.compare(ack.appliedValue(), item.proposal().proposedValue()) != 0) {
                throw new IllegalArgumentException("Applied acknowledgement requires a new version and the exact applied value");
            }
            var policy = current(item.proposal().target(), item.proposal().source());
            if (policy == null || !policy.version().equals(item.proposal().baseVersion())) throw new IllegalArgumentException("Policy changed before enforcement acknowledgement");
            double proposed = item.proposal().proposedValue();
            Policy updated = new Policy(policy.target(), ack.policyVersion(), policy.validUntil(),
                    item.proposal().action() == Action.LEAKY_BUCKET ? Double.valueOf(proposed) : policy.bucketRate(), policy.healthyBucketTarget(),
                    item.proposal().action() == Action.RETRY_BUDGET ? Double.valueOf(proposed) : policy.retryRatio(),
                    item.proposal().action() == Action.CHAIN_DEADLINE ? Double.valueOf(proposed) : policy.deadlineMs());
            audit.delivery(replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.APPLIED, "Enforcement confirmed by Component 3", item.attempts() + 1, now, ack.policyVersion()));
            putPolicy(updated, item.proposal().source());
        } else {
            audit.delivery(replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SENT, "Accepted by Component 3; waiting for enforcement", item.attempts() + 1, now, null));
        }
    }
    private double value(Policy policy, Action action) {
        Double value = switch (action) { case LEAKY_BUCKET -> policy.bucketRate(); case RETRY_BUDGET -> policy.retryRatio(); case CHAIN_DEADLINE -> policy.deadlineMs(); };
        if (value == null) throw new IllegalArgumentException("Current policy value is missing"); return value;
    }
    /**
     * An approved change that is not yet sent moves to the new policy version when its own value did not change there
     * (another lever of the same policy was applied first, e.g. a rate change before a retry change). Pending, sent or
     * outdated changes are superseded as before.
     */
    private boolean rebase(PolicyChange item, Policy policy) {
        if (item.decision() != Decision.APPROVED || item.delivery() == Delivery.SENT) return false;
        Double value = switch (item.proposal().action()) {
            case LEAKY_BUCKET -> policy.bucketRate(); case RETRY_BUDGET -> policy.retryRatio(); case CHAIN_DEADLINE -> policy.deadlineMs();
        };
        if (value == null || Double.compare(value, item.proposal().currentValue()) != 0) return false;
        var p = item.proposal();
        var moved = new Proposal(p.id(), p.target(), p.action(), p.source(), policy.version(), p.currentValue(), p.proposedValue(), p.unit(),
                p.generatedAt(), p.expiresAt(), p.measurements(), p.evidence());
        history.put(p.id(), new PolicyChange(moved, item.decision(), item.reviewer(), item.decidedAt(), item.delivery(),
                "Moved to policy version " + policy.version() + " (another change of this policy was applied first)", item.attempts(), item.lastAttempt(), item.appliedVersion()));
        return true;
    }
    // ---------- joint recommendations ----------

    /** Maps each part to its joint, and each joint to its parts in order. Empty unless joint recalibration is on. */
    private final Map<UUID, UUID> jointOf = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> joints = new LinkedHashMap<>();

    public synchronized boolean isJointPart(UUID id) { return jointOf.containsKey(id); }
    public synchronized UUID jointOf(UUID id) { return jointOf.get(id); }
    public synchronized List<PolicyChange> jointParts(UUID jointId) {
        return joints.getOrDefault(jointId, List.of()).stream().map(history::get).filter(Objects::nonNull).toList();
    }
    /**
     * Offers the parts of one joint recommendation (a rate proposal and one deadline proposal per chain). Re-offering the same
     * parts (same base versions and values) keeps their ids, decisions and joint id and refreshes the evidence; anything
     * else replaces the earlier joint. Parts are never approved automatically.
     */
    public synchronized List<PolicyChange> offerJoint(List<Proposal> parts) {
        if (parts.size() < 2) throw new IllegalArgumentException("A joint recommendation has at least two parts");
        var out = new ArrayList<PolicyChange>();
        for (Proposal p : parts) out.add(offer(p));
        var ids = out.stream().map(c -> c.proposal().id()).toList();
        UUID existing = jointOf.get(ids.get(0));
        boolean same = existing != null && joints.get(existing).equals(ids);
        boolean consistent = out.stream().map(PolicyChange::decision).distinct().count() == 1;
        if (!same || !consistent) {
            if (!consistent || ids.stream().anyMatch(jointOf::containsKey)) {
                // parts of an earlier joint mixed with new ones: start a clean joint
                for (var c : out) if (active(c)) replace(c, c.decision(), c.reviewer(), c.decidedAt(), Delivery.SUPERSEDED, "Replaced by a new joint recommendation", c.attempts(), c.lastAttempt(), null);
                cascadeJoints();
                out.clear();
                for (Proposal p : parts) out.add(offer(new Proposal(UUID.randomUUID(), p.target(), p.action(), p.source(), p.baseVersion(), p.currentValue(),
                        p.proposedValue(), p.unit(), p.generatedAt(), p.expiresAt(), p.measurements(), p.evidence())));
                ids = out.stream().map(c -> c.proposal().id()).toList();
            }
            UUID joint = UUID.randomUUID();
            joints.put(joint, List.copyOf(ids));
            for (UUID id : ids) jointOf.put(id, joint);
        }
        cascadeJoints();
        return out.stream().map(c -> history.get(c.proposal().id())).toList();
    }
    /** The active joint recommendation that has a part for {@code target}, if any. */
    public synchronized java.util.Optional<UUID> activeJoint(String target, Source source) {
        expire();
        return joints.entrySet().stream().filter(e -> e.getValue().stream().map(history::get).filter(Objects::nonNull)
                        .anyMatch(c -> active(c) && c.proposal().target().equals(target) && key(target, c.proposal().source()).equals(key(target, source))))
                .map(Map.Entry::getKey).reduce((a, b) -> b);
    }
    /** Like {@link #withdraw}, but leaves the parts of joint recommendations alone (they are withdrawn only for priority reasons). */
    public synchronized void withdrawExceptJoint(String target, Action action, Source source, String reason) {
        for (var item : new ArrayList<>(history.values())) {
            if (item.proposal().target().equals(target) && item.proposal().action() == action && !jointOf.containsKey(item.proposal().id())
                    && key(target, item.proposal().source()).equals(key(target, source)) && active(item)) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, reason, item.attempts(), item.lastAttempt(), null);
            }
        }
        cascadeJoints();
    }
    /** The latest applied deadline part of a joint recommendation for this chain, or null. */
    public synchronized PolicyChange appliedJointDeadline(String chain, Source source) {
        PolicyChange latest = null;
        for (UUID id : jointOf.keySet()) {
            var c = history.get(id);
            if (c != null && c.delivery() == Delivery.APPLIED && c.proposal().action() == Action.CHAIN_DEADLINE && c.proposal().target().equals(chain)
                    && key(chain, c.proposal().source()).equals(key(chain, source)) && (latest == null || c.lastAttempt().isAfter(latest.lastAttempt()))) latest = c;
        }
        return latest;
    }
    // ---------------------------------------------------------------- practice cards (synthetic, MOCK) ----------------------------------------------------------------

    /**
     * Removes synthetic (MOCK) cards from the in-memory list, oldest first, until at most {@code keep} remain; with {@code keep}
     * = 0 also their MOCK policy references. Live cards and the audit trail are never touched. A joint card is removed whole.
     */
    public synchronized int removeSynthetic(int keep) {
        var synthetic = history.values().stream().filter(c -> c.proposal().source() == Source.MOCK)
                .sorted(java.util.Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt())).map(c -> c.proposal().id()).toList();
        var remove = new java.util.LinkedHashSet<UUID>(synthetic.subList(0, Math.max(0, synthetic.size() - Math.max(0, keep))));
        for (UUID id : new ArrayList<>(remove)) { UUID joint = jointOf.get(id); if (joint != null) remove.addAll(joints.getOrDefault(joint, List.of())); }
        for (UUID id : remove) {
            history.remove(id);
            UUID joint = jointOf.remove(id);
            if (joint != null) joints.remove(joint);
        }
        if (keep <= 0) policies.keySet().removeIf(k -> k.startsWith("MOCK:"));
        return remove.size();
    }
    /** Withdraws every part of the active joint recommendations that touch {@code target}. */
    public synchronized void withdrawJoint(String target, Source source, String reason) {
        for (var joint : new ArrayList<>(joints.entrySet())) {
            var parts = joint.getValue().stream().map(history::get).filter(Objects::nonNull).toList();
            if (parts.stream().noneMatch(c -> active(c) && c.proposal().target().equals(target) && key(target, c.proposal().source()).equals(key(target, source)))) continue;
            for (var c : parts) if (active(c)) replace(c, c.decision(), c.reviewer(), c.decidedAt(), Delivery.SUPERSEDED, reason, c.attempts(), c.lastAttempt(), null);
        }
    }
    /** All parts stand or fall together: a superseded, expired or rejected part withdraws the joint's other active parts. */
    private void cascadeJoints() {
        for (var joint : joints.values()) {
            var parts = joint.stream().map(history::get).filter(Objects::nonNull).toList();
            var gone = parts.stream().filter(c -> c.decision() == Decision.REJECTED || c.delivery() == Delivery.SUPERSEDED || c.delivery() == Delivery.EXPIRED).findFirst();
            if (gone.isEmpty()) continue;
            for (var c : parts) if (active(c)) replace(c, c.decision(), c.reviewer(), c.decidedAt(), Delivery.SUPERSEDED,
                    "A part of this joint recommendation was withdrawn (" + gone.get().detail() + ")", c.attempts(), c.lastAttempt(), null);
        }
    }
    /** The joint recommendations for the API: id, parts and their state, newest first. */
    public synchronized List<Map<String, Object>> joints() {
        expire();
        var out = new ArrayList<Map<String, Object>>();
        for (var joint : joints.entrySet()) {
            var parts = joint.getValue().stream().map(history::get).filter(Objects::nonNull).toList();
            if (parts.isEmpty()) continue;
            var view = new LinkedHashMap<String, Object>();
            view.put("jointId", joint.getKey().toString());
            view.put("parts", parts.stream().map(c -> Map.of("proposalId", c.proposal().id().toString(), "action", c.proposal().action().name(),
                    "target", c.proposal().target(), "currentValue", c.proposal().currentValue(), "proposedValue", c.proposal().proposedValue())).toList());
            view.put("decision", parts.get(0).decision().name()); view.put("delivery", parts.get(0).delivery().name());
            view.put("generatedAt", parts.get(0).proposal().generatedAt().toString());
            out.add(0, view);
        }
        return out.size() > 50 ? List.copyOf(out.subList(0, 50)) : List.copyOf(out);
    }

    /** Rate and retry changes approved by a reviewer (not automatically) for a target at or after {@code since}. */
    public synchronized boolean reviewerApprovedSince(String target, Source source, Instant since) { return reviewerApprovedSince(target, source, since, null); }
    /** As above, leaving out the parts of joint recommendation {@code exceptJoint} (one decision with the change being verified). */
    public synchronized boolean reviewerApprovedSince(String target, Source source, Instant since, UUID exceptJoint) {
        return history.values().stream().anyMatch(item -> item.proposal().target().equals(target) && item.proposal().action() != Action.CHAIN_DEADLINE
                && key(target, item.proposal().source()).equals(key(target, source)) && item.decision() == Decision.APPROVED
                && item.reviewer() != null && !item.reviewer().startsWith("c4-auto") && item.decidedAt() != null && !item.decidedAt().isBefore(since)
                && (exceptJoint == null || !exceptJoint.equals(jointOf.get(item.proposal().id()))));
    }
    private boolean sameControl(Proposal a, Proposal b) { return a.action() == b.action() && key(a.target(), a.source()).equals(key(b.target(), b.source())); }
    private boolean active(PolicyChange item) { return item.delivery() != Delivery.APPLIED && item.delivery() != Delivery.EXPIRED && item.delivery() != Delivery.SUPERSEDED && item.decision() != Decision.REJECTED; }
    private void expire() {
        for (var item : new ArrayList<>(history.values())) if (active(item)) {
            if (!item.proposal().expiresAt().isAfter(clock.instant())) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.EXPIRED, "Original proposal validity ended", item.attempts(), item.lastAttempt(), null);
            } else if (item.decision() == Decision.PENDING_APPROVAL) {
                // Undecided recommendations must stay backed by current evidence; an approved one is bound only by its expiry.
                Double end = item.proposal().measurements().get("windowEndEpochSeconds");
                if (end == null || !Double.isFinite(end)
                        || clock.instant().getEpochSecond() - end > settings.cycle().toSeconds() * 2 + evidenceDelay.toSeconds()) {
                    replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SUPERSEDED, "Evidence became stale; collect a new window", item.attempts(), item.lastAttempt(), null);
                }
            }
        }
        cascadeJoints();
    }
    private PolicyChange replace(PolicyChange original, Decision decision, String reviewer, Instant decidedAt,
            Delivery delivery, String detail, int attempts, Instant lastAttempt, String version) {
        var updated = new PolicyChange(original.proposal(), decision, reviewer, decidedAt, delivery, detail, attempts, lastAttempt, version);
        history.put(original.proposal().id(), updated); return updated;
    }
}
