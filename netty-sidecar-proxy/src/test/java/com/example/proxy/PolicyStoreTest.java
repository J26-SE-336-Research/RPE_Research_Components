package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PolicyStoreTest {
    private static final String SPEC = "{\"deadlineMs\":1200,\"retry\":{\"budget\":2,\"budgetRatio\":0.1},\"leakyBucket\":{\"ratePerSec\":0}}";

    private static String doc(String epoch, String state, String policy) {
        return "{\"epoch\":\"" + epoch + "\",\"version\":1,\"hash\":\"declared\",\"state\":\"" + state + "\",\"policy\":" + policy + "}";
    }

    @Test
    void contentHashMatchesTheOperatorHash() {
        // the same vectors as k8s/operator TestSpecHashVectors: both sides must agree byte for byte
        assertEquals("f114f22e7849eefa", PolicyStore.contentHash(doc("p-v1", "staged", SPEC)));
        assertEquals("6538ffb2b2a4413d", PolicyStore.contentHash(doc("p-v1", "staged",
                "{\"b\":[1,2.5,{\"z\":null,\"a\":true}],\"a\":\"x\\\"y\",\"n\":1.0,\"m\":0.1,\"e\":1e-7,\"big\":1e16,\"u\":\"caf\\u00e9 \\u0001\"}")));
        assertEquals("74234e98afe7498f", PolicyStore.contentHash("{\"epoch\":\"p-v1\",\"state\":\"staged\"}"));
        assertEquals(PolicyStore.contentHash(doc("a", "staged", "{\"x\":1,\"y\":[2]}")),
                PolicyStore.contentHash(doc("b", "active", "{ \"y\" : [2], \"x\" : 1.0 }")), "key order and formatting do not matter");
    }

    @Test
    void storedHashIsComputedNotDeclared() {
        PolicyStore store = new PolicyStore(null);
        PolicyStore.Entry entry = store.put("p-v1", 1, PolicyStore.State.STAGED, doc("p-v1", "staged", SPEC));
        assertEquals("f114f22e7849eefa", entry.hash());
        assertTrue(store.toJson().contains("\"hash\":\"f114f22e7849eefa\""));
    }

    @Test
    void requestsArePinnedToTheEpochTheyCarry() {
        PolicyStore store = new PolicyStore(null);
        store.put("pay-v1", 1, PolicyStore.State.ACTIVE, doc("pay-v1", "active", SPEC));
        store.put("pay-v2", 2, PolicyStore.State.STAGED, doc("pay-v2", "staged", SPEC));
        // staged but carried: served under the carried epoch, no mismatch
        PolicyStore.Pin v2 = store.pin("pay-v2", false);
        assertEquals("pay-v2", v2.epoch());
        assertFalse(v2.mismatched());
        assertEquals(1, store.inflight("pay-v2"));
        store.release(v2);
        assertEquals(0, store.inflight("pay-v2"));
        // old epoch still held: served under it
        assertEquals("pay-v1", store.pin("pay-v1", false).epoch());
        assertEquals(0, store.mismatched());
    }

    @Test
    void anEpochThatIsNoLongerHeldIsAMixedPolicyHop() {
        PolicyStore store = new PolicyStore(null);
        store.put("pay-v2", 2, PolicyStore.State.ACTIVE, doc("pay-v2", "active", SPEC));
        PolicyStore.Pin pin = store.pin("pay-v1", false);
        assertEquals("pay-v2", pin.epoch());
        assertTrue(pin.mismatched());
        assertEquals(1, store.mismatched());
        assertTrue(store.toJson().contains("\"mismatched\":1"));
    }

    @Test
    void epochsOfOtherPoliciesAndUnpinnedRequestsAreNotMixed() {
        PolicyStore store = new PolicyStore(null);
        assertNull(store.pin("pay-v1", false), "no policy held: nothing applies");
        store.put("pay-v2", 2, PolicyStore.State.ACTIVE, doc("pay-v2", "active", SPEC));
        assertFalse(store.pin("inventory-v4", false).mismatched(), "the gateway stamped another policy's epoch");
        assertFalse(store.pin("", false).mismatched(), "a request without an epoch takes the active policy");
        assertEquals(0, store.mismatched());
    }

    @Test
    void theGatewayServesTheEpochItStamped() {
        PolicyStore store = new PolicyStore(null);
        store.put("pay-v2", 2, PolicyStore.State.ACTIVE, doc("pay-v2", "active", SPEC));
        PolicyStore.Pin pin = store.pin("pay-v2", true);
        assertEquals("pay-v2", pin.epoch());
        assertNull(store.pin("", true), "nothing stamped, nothing pinned");
    }

    @Test
    void metricsExposeHeldEpochsInflightAndMismatches() {
        PolicyStore store = new PolicyStore(null);
        store.put("pay-v1", 1, PolicyStore.State.ACTIVE, doc("pay-v1", "active", SPEC));
        store.pin("pay-v1", false);
        StringBuilder sb = new StringBuilder();
        store.render(sb, "service=\"payment-svc\",pod=\"p\"");
        String text = sb.toString();
        assertTrue(text.contains("rpe_policy_info{service=\"payment-svc\",pod=\"p\",epoch=\"pay-v1\",version=\"1\",hash=\"f114f22e7849eefa\",state=\"active\"} 1"), text);
        assertTrue(text.contains("rpe_policy_inflight_requests{service=\"payment-svc\",pod=\"p\",epoch=\"pay-v1\"} 1"), text);
        assertTrue(text.contains("rpe_policy_mismatch_total{service=\"payment-svc\",pod=\"p\"} 0"), text);
    }

    @Test
    void familyStripsTheVersionSuffix() {
        assertEquals("payment-deadline", PolicyStore.family("payment-deadline-v12"));
        assertEquals("e42", PolicyStore.family("e42"));
    }
}
