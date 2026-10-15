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

/** In-memory PoC outbox. Approval, transport acceptance and applied policy are distinct states. */
@Service
public class PolicyWorkflow {
    private final Clock clock;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    private final Map<String, Policy> policies = new LinkedHashMap<>();
    private final Map<UUID, PolicyChange> history = new LinkedHashMap<>();
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
                || java.time.Duration.between(observedAt, clock.instant()).compareTo(settings.cycle().multipliedBy(2)) > 0) return false;
        String version = "prometheus-config:" + Long.toString((long) deadline);
        Policy existing = current(target, Source.TEMPO);
        if (existing != null && !existing.version().startsWith("prometheus-config:")
                && !Objects.equals(existing.deadlineMs(), deadline)) return false;
        if (existing != null && !existing.version().startsWith("prometheus-config:")) version = existing.version();
        putPolicy(new Policy(target, version, observedAt.plus(settings.cycle().multipliedBy(2)), null, null, null, deadline), Source.TEMPO);
        return true;
    }
    public synchronized Instant lastApplied(String target, Action action, Source source) {
        return history.values().stream().filter(item -> item.proposal().target().equals(target) && item.proposal().action() == action
                && key(target, item.proposal().source()).equals(key(target, source)) && item.delivery() == Delivery.APPLIED)
                .map(PolicyChange::lastAttempt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
    public synchronized PolicyChange offer(Proposal proposal) {
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
            if (sameControl(previous.proposal(), proposal) && active(previous)) {
                if (previous.proposal().baseVersion().equals(proposal.baseVersion())
                        && Double.compare(previous.proposal().proposedValue(), proposal.proposedValue()) == 0) return previous;
                replace(previous, previous.decision(), previous.reviewer(), previous.decidedAt(), Delivery.SUPERSEDED,
                        "Superseded by newer evidence", previous.attempts(), previous.lastAttempt(), null);
            }
        }
        Decision decision = proposal.action() == Action.CHAIN_DEADLINE ? Decision.PENDING_APPROVAL : Decision.AUTOMATIC;
        Delivery delivery = proposal.source() == Source.MOCK || connections.component3Url().isBlank()
                ? Delivery.WAITING_FOR_COMPONENT_3 : Delivery.READY;
        var change = new PolicyChange(proposal, decision, decision == Decision.AUTOMATIC ? "adaptive-controller" : null,
                decision == Decision.AUTOMATIC ? clock.instant() : null, delivery,
                proposal.source() == Source.MOCK ? "Synthetic example; never sent to Component 3" : "Awaiting delivery and enforcement acknowledgement", 0, null, null);
        history.put(proposal.id(), change);
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
        if (item.proposal().action() != Action.CHAIN_DEADLINE) throw new IllegalArgumentException("Only deadlines require human review");
        if (review == null || review.reviewer() == null || review.reviewer().isBlank()) throw new IllegalArgumentException("Reviewer is required");
        if (!active(item)) throw new IllegalArgumentException("This deadline proposal is no longer current");
        Decision decision = review.approve() ? Decision.APPROVED : Decision.REJECTED;
        if (item.decision() != Decision.PENDING_APPROVAL) {
            if (item.decision() == decision && review.reviewer().equals(item.reviewer())) return item;
            throw new IllegalArgumentException("Deadline already has a decision");
        }
        return replace(item, decision, review.reviewer(), clock.instant(), item.delivery(), review.comment(), item.attempts(), item.lastAttempt(), null);
    }
    public synchronized List<PolicyChange> list() { expire(); return history.values().stream().sorted(java.util.Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt()).reversed()).limit(100).toList(); }
    public synchronized List<PolicyChange> deliverable() {
        expire(); return history.values().stream().filter(this::active)
                .filter(item -> item.proposal().source() != Source.MOCK && (item.decision() == Decision.AUTOMATIC || item.decision() == Decision.APPROVED))
                .filter(item -> item.delivery() != Delivery.SENT && item.attempts() < 5
                        && (item.lastAttempt() == null || clock.instant().isAfter(item.lastAttempt().plusSeconds(30))))
                .limit(100).toList();
    }
    public synchronized PolicyChange find(UUID id) {
        var item = history.get(id); if (item == null) throw new IllegalArgumentException("Unknown proposal"); return item;
    }
    public synchronized void deliveryResult(UUID id, Acknowledgement ack, String failure) {
        expire(); var item = find(id);
        if (!active(item) || !(item.decision() == Decision.AUTOMATIC || item.decision() == Decision.APPROVED)) return;
        Instant now = clock.instant();
        if (failure != null) {
            replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.FAILED, failure, item.attempts() + 1, now, null); return;
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
            replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.APPLIED, "Enforcement confirmed by Component 3", item.attempts() + 1, now, ack.policyVersion());
            putPolicy(updated, item.proposal().source());
        } else {
            replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.SENT, "Accepted by Component 3; waiting for enforcement", item.attempts() + 1, now, null);
        }
    }
    private double value(Policy policy, Action action) {
        Double value = switch (action) { case LEAKY_BUCKET -> policy.bucketRate(); case RETRY_BUDGET -> policy.retryRatio(); case CHAIN_DEADLINE -> policy.deadlineMs(); };
        if (value == null) throw new IllegalArgumentException("Current policy value is missing"); return value;
    }
    private boolean sameControl(Proposal a, Proposal b) { return a.action() == b.action() && key(a.target(), a.source()).equals(key(b.target(), b.source())); }
    private boolean active(PolicyChange item) { return item.delivery() != Delivery.APPLIED && item.delivery() != Delivery.EXPIRED && item.delivery() != Delivery.SUPERSEDED && item.decision() != Decision.REJECTED; }
    private void expire() {
        for (var item : new ArrayList<>(history.values())) if (active(item)) {
            if (!item.proposal().expiresAt().isAfter(clock.instant())) {
                replace(item, item.decision(), item.reviewer(), item.decidedAt(), Delivery.EXPIRED, "Original proposal validity ended", item.attempts(), item.lastAttempt(), null);
            } else {
                Double end = item.proposal().measurements().get("windowEndEpochSeconds");
                if (end == null || !Double.isFinite(end) || clock.instant().getEpochSecond() - end > settings.cycle().toSeconds() * 2) {
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
