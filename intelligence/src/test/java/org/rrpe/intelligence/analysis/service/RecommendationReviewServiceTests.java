package org.rrpe.intelligence.analysis.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.rrpe.intelligence.analysis.api.RecommendationReviewRequest;
import org.rrpe.intelligence.analysis.api.RecommendationReviewRequest.Decision;
import org.rrpe.intelligence.analysis.model.*;
import org.rrpe.intelligence.analysis.model.RecommendationReview.Status;
import org.rrpe.intelligence.analysis.store.InMemoryRecommendationReviewStore;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationReviewServiceTests {
    private static final Instant START = Instant.parse("2026-10-06T10:00:00Z");
    private final TestClock clock = new TestClock();
    private final InMemoryRecommendationReviewStore store = new InMemoryRecommendationReviewStore();
    private final RecommendationReviewService service = new RecommendationReviewService(store, clock);

    private static class TestClock extends Clock {
        private Instant now = START;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }

    private RecommendationPlan plan() {
        return new RecommendationPlan("1.0", "ADVISORY", "review-service", HealthStatus.OVERLOADED,
                START.minusSeconds(10), START, START.plusSeconds(300), List.of("test evidence"),
                List.of(new RecommendationPlan.Recommendation(RecommendationPlan.Action.DISABLE_RETRIES,
                        Map.of("maxAttempts", 1), "test")));
    }

    private ChainDeadlineRecommendation deadlinePlan() {
        return new ChainDeadlineRecommendation("1.0", "ADVISORY", "test-chain", "READY", true, true,
                "CHANGE_CHAIN_DEADLINE", 1440L, "test", null, 1.2, START, START.plusSeconds(300));
    }
    private RecommendationReviewRequest decision(RecommendationReview review, Decision action) {
        return new RecommendationReviewRequest(review.recommendationId(), action, "reviewer", "test comment");
    }

    @Test
    void automaticServiceDecisionsExpireAndPreserveTheirAudit() {
        var automatic = service.submitServicePlan(plan());
        assertEquals(Status.AUTO_APPROVED, automatic.status());
        assertEquals("automatic-policy-controller", automatic.reviewer());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.review(decision(automatic, Decision.APPROVE))).getStatusCode().value());
        clock.now = START.plusSeconds(300);
        var expired = service.find(automatic.recommendationId());
        assertEquals(Status.EXPIRED, expired.status());
        assertEquals(automatic.reviewedAt(), expired.reviewedAt());
        assertEquals(automatic.reviewer(), expired.reviewer());
    }

    @Test
    void exactExpiryBoundaryClosesPendingReviewWithoutRecordingAnApproval() {
        var pending = service.submitDeadlinePlan(deadlinePlan());
        clock.now = START.plusSeconds(300);
        var expired = service.review(decision(pending, Decision.APPROVE));
        assertEquals(Status.EXPIRED, expired.status());
        assertNull(expired.reviewedAt());
        assertNull(expired.reviewer());
        assertEquals(pending.deadlinePlan(), expired.deadlinePlan());
        assertEquals(expired, service.review(decision(pending, Decision.REJECT)));
        assertEquals(expired, service.findLatest(100).get(0));
    }

    @Test
    void approvalDoesNotRestartValidityAndExpiryKeepsTheOriginalAuditRecord() {
        var pending = service.submitDeadlinePlan(deadlinePlan());
        clock.now = START.plusSeconds(299);
        var approved = service.review(decision(pending, Decision.APPROVE));
        assertEquals(Status.APPROVED, approved.status());
        assertEquals(START.plusSeconds(300), approved.expiresAt());
        clock.now = START.plusSeconds(300);
        var expired = service.find(pending.recommendationId());
        assertEquals(Status.EXPIRED, expired.status());
        assertEquals(approved.reviewedAt(), expired.reviewedAt());
        assertEquals(approved.reviewer(), expired.reviewer());
        assertEquals(approved.comment(), expired.comment());
    }

    @Test
    void expiryDuringStorageUpdateIsRecheckedBeforeReturningTheResult() {
        var advancingStore = new InMemoryRecommendationReviewStore() {
            @Override
            public boolean compareAndSet(RecommendationReview expected, RecommendationReview replacement) {
                boolean updated = super.compareAndSet(expected, replacement);
                if (replacement.status() == Status.APPROVED) clock.now = START.plusSeconds(300);
                return updated;
            }
        };
        var workflow = new RecommendationReviewService(advancingStore, clock);
        var pending = workflow.submitDeadlinePlan(deadlinePlan());
        assertEquals(Status.EXPIRED, workflow.review(decision(pending, Decision.APPROVE)).status());
    }

    @Test
    void rejectedHistoryRemainsRejectedAfterPlanExpiry() {
        var pending = service.submitDeadlinePlan(deadlinePlan());
        var rejected = service.review(decision(pending, Decision.REJECT));
        clock.now = START.plusSeconds(600);
        assertEquals(rejected, service.find(pending.recommendationId()));
        var conflict = assertThrows(ResponseStatusException.class, () -> service.review(decision(pending, Decision.APPROVE)));
        assertEquals(409, conflict.getStatusCode().value());
    }

    @Test
    void simultaneousOppositeDecisionsProduceOneWinner() throws Exception {
        var pending = service.submitDeadlinePlan(deadlinePlan());
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var approve = executor.submit(() -> attempt(pending, Decision.APPROVE, gate));
            var reject = executor.submit(() -> attempt(pending, Decision.REJECT, gate));
            gate.countDown();
            boolean yes = approve.get(5, TimeUnit.SECONDS), no = reject.get(5, TimeUnit.SECONDS);
            assertNotEquals(yes, no);
            assertEquals(yes ? Status.APPROVED : Status.REJECTED, service.find(pending.recommendationId()).status());
        } finally { executor.shutdownNow(); }
    }

    private boolean attempt(RecommendationReview pending, Decision action, CountDownLatch gate) throws Exception {
        gate.await();
        try { service.review(decision(pending, action)); return true; }
        catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return false; }
    }

    @Test
    void storageRejectsDuplicateAndEditedPlansAndReturnsAnImmutableBoundedList() {
        var pending = service.submitDeadlinePlan(deadlinePlan());
        assertFalse(store.insert(pending));
        var edited = new RecommendationPlan("1.0", "ADVISORY", "edited-service", HealthStatus.OVERLOADED,
                START.minusSeconds(10), START, START.plusSeconds(600), List.of(), plan().recommendations());
        var tampered = new RecommendationReview(pending.recommendationId(), edited, null, Status.APPROVED,
                pending.submittedAt(), START, "reviewer", null);
        assertThrows(IllegalArgumentException.class, () -> store.compareAndSet(pending, tampered));
        assertEquals(pending, store.findById(pending.recommendationId()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> store.findLatest(1).clear());
        assertThrows(IllegalArgumentException.class, () -> store.findLatest(101));
    }

    @Test
    void expiredAndNonActionablePlansCannotBeQueued() {
        clock.now = START.plusSeconds(300);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.submitDeadlinePlan(deadlinePlan())).getStatusCode().value());
        clock.now = START;
        var healthy = new RecommendationPlan("1.0", "ADVISORY", "healthy", HealthStatus.HEALTHY,
                START.minusSeconds(10), START, START.plusSeconds(300), List.of(), List.of());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.submitServicePlan(healthy)).getStatusCode().value());
    }
}
