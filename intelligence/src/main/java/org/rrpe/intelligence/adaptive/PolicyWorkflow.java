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
    public synchronized Instant lastApplied(String target, Action action, Source source) {
        return history.values().stream().filter(item -> item.proposal().target().equals(target) && item.proposal().action() == action
                && key(target, item.proposal().source()).equals(key(target, source)) && item.delivery() == Delivery.APPLIED)
                .map(PolicyChange::lastAttempt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
    public synchronized PolicyChange offer(Proposal proposal) { return offer(proposal, null); }
    /**
     * Rate v2 automatic approval: with {@code auto} (reviewer {@code c4-auto} or {@code c4-auto-revert}, chosen by the engine's
     * guard) a rate or retry recommendation is created APPROVED and audited as decided. Never for a chain deadline or a
     * synthetic (MOCK) recommendation; without {@code auto} every recommendation waits for a human exactly as before.
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
    /** Rate and retry changes approved by a reviewer (not automatically) for a target at or after {@code since}. */
    public synchronized boolean reviewerApprovedSince(String target, Source source, Instant since) {
        return history.values().stream().anyMatch(item -> item.proposal().target().equals(target) && item.proposal().action() != Action.CHAIN_DEADLINE
                && key(target, item.proposal().source()).equals(key(target, source)) && item.decision() == Decision.APPROVED
                && item.reviewer() != null && !item.reviewer().startsWith("c4-auto") && item.decidedAt() != null && !item.decidedAt().isBefore(since));
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
    }
    private PolicyChange replace(PolicyChange original, Decision decision, String reviewer, Instant decidedAt,
            Delivery delivery, String detail, int attempts, Instant lastAttempt, String version) {
        var updated = new PolicyChange(original.proposal(), decision, reviewer, decidedAt, delivery, detail, attempts, lastAttempt, version);
        history.put(original.proposal().id(), updated); return updated;
    }
}
