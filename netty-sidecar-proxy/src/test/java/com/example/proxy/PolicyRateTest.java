package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A delivered rate-limit policy sets the rate of the inbound leaky bucket when it is activated, and only the rate. */
class PolicyRateTest {
    private static String doc(String epoch, String policy) {
        return "{\"epoch\":\"" + epoch + "\",\"version\":1,\"state\":\"active\",\"policy\":" + policy + "}";
    }

    private static PolicyStore.Entry entry(String epoch, String policy) {
        return new PolicyStore(null).put(epoch, 1, PolicyStore.State.ACTIVE, doc(epoch, policy));
    }

    private static String metric(BackpressureController controller, String name) {
        return controller.prometheusText().lines()
                .filter(line -> line.startsWith(name + " "))
                .findFirst().orElseThrow().split(" ")[1];
    }

    private static String rate(BackpressureController controller) {
        return metric(controller, "rpe_backpressure_inbound_rate_per_second");
    }

    private static String capacity(BackpressureController controller) {
        return metric(controller, "rpe_backpressure_inbound_queue_capacity");
    }

    @Test
    void parsesOnlyTheRateOfADeliveredDocument() {
        assertEquals(120.0, PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":120}}")));
        assertEquals(0.5, PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":0.5}}")));
        assertEquals(120.0, PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":120,\"queueCapacity\":300,\"burst\":40}}")),
                "queueCapacity and burst are ignored; only the rate is read");
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"deadlineMs\":800}")), "a policy without a rate changes nothing");
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"queueCapacity\":300}}")), "a capacity alone changes nothing");
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":0}}")));
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":-5}}")));
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":2000000}}")));
        assertNull(PolicyStore.leakyBucketRate(doc("p-v1", "{\"leakyBucket\":{\"ratePerSec\":\"fast\"}}")));
        assertNull(PolicyStore.leakyBucketRate("not json"));
        assertNull(PolicyStore.leakyBucketRate(null));
    }

    @Test
    void activatingARateLimitPolicySetsTheInboundRate() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        BackpressureController controller = new BackpressureController(config);
        assertEquals("100.0", rate(controller), "the configured default");

        ActivePolicy.apply(config, entry("pay-v1", "{\"leakyBucket\":{\"ratePerSec\":120}}"), controller);
        assertEquals("120.0", rate(controller));
        ActivePolicy.apply(config, entry("pay-v2", "{\"leakyBucket\":{\"ratePerSec\":2.5}}"), controller);
        assertEquals("2.5", rate(controller));
    }

    @Test
    void aPolicyNeverChangesTheQueueCapacity() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        BackpressureController controller = new BackpressureController(config);
        assertEquals("200", capacity(controller), "the configured default");

        ActivePolicy.apply(config, entry("pay-v1", "{\"leakyBucket\":{\"ratePerSec\":120,\"queueCapacity\":50}}"), controller);
        assertEquals("120.0", rate(controller), "the rate is applied");
        assertEquals("200", capacity(controller), "the queue capacity in the policy is ignored");
        ActivePolicy.apply(config, entry("pay-v2", "{\"leakyBucket\":{\"queueCapacity\":50}}"), controller);
        assertEquals("200", capacity(controller), "a capacity-only policy changes nothing");
        assertEquals("120.0", rate(controller));
    }

    @Test
    void aPolicyWithoutAValidRateKeepsTheCurrentRate() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        BackpressureController controller = new BackpressureController(config);
        ActivePolicy.apply(config, entry("pay-v1", "{\"leakyBucket\":{\"ratePerSec\":120}}"), controller);

        ActivePolicy.apply(config, entry("pay-v2", "{\"retry\":{\"budgetRatio\":0.2}}"), controller);
        assertEquals("120.0", rate(controller), "a retry-ratio-only version must not undo the rate limit");
        ActivePolicy.apply(config, entry("pay-v3", "{\"leakyBucket\":{\"ratePerSec\":-1}}"), controller);
        assertEquals("120.0", rate(controller), "an invalid rate is ignored");
        ActivePolicy.apply(config, entry("pay-v4", "{\"leakyBucket\":{\"ratePerSec\":5000000}}"), controller);
        assertEquals("120.0", rate(controller), "an out-of-range rate is ignored");
    }

    @Test
    void aStagedPolicyIsNotEnforcedUntilItIsActivated() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        BackpressureController controller = new BackpressureController(config);
        PolicyStore store = new PolicyStore(active -> ActivePolicy.apply(config, active, controller));
        store.put("pay-v1", 1, PolicyStore.State.STAGED, doc("pay-v1", "{\"leakyBucket\":{\"ratePerSec\":7}}"));
        assertEquals("100.0", rate(controller), "staged: held inactive");
        assertTrue(store.activate("pay-v1"));
        assertEquals("7.0", rate(controller), "activated: enforced");
        store.put("pay-v2", 2, PolicyStore.State.ACTIVE, doc("pay-v2", "{\"leakyBucket\":{\"ratePerSec\":9}}"));
        assertEquals("9.0", rate(controller), "a policy delivered directly as active is enforced too");
    }

    @Test
    void applyPolicyRateRejectsOutOfRangeValuesWithoutChangingAnything() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        assertFalse(controller.applyPolicyRate(0));
        assertFalse(controller.applyPolicyRate(Double.NaN));
        assertFalse(controller.applyPolicyRate(1_000_001));
        assertEquals("100.0", rate(controller));
        assertEquals("200", capacity(controller));
        assertTrue(controller.applyPolicyRate(1_000_000));
        assertEquals("1000000.0", rate(controller));
        assertEquals("200", capacity(controller));
    }
}
