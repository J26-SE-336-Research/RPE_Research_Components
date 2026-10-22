package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rate v2 (R4): a Component 3 sync updates the current rate and retry ratio but keeps C4's healthy target. */
class SyncHealthyRateTests {
    private volatile String effective = "{}";
    private HttpServer server;
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private PolicyWorkflow workflow;
    private Component3Bridge bridge;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/k8s/", exchange -> {
            boolean known = exchange.getRequestURI().getPath().equals("/api/k8s/effective");
            byte[] bytes = (known ? effective : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(known ? 200 : 404, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var connections = new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200", "rpe-demo",
                List.of("payment-svc"), "gateway", Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000, url, "", Map.of());
        workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock);
        bridge = new Component3Bridge(workflow, connections, AdaptiveEngineTests.settings());
    }
    @AfterEach void stop() { server.stop(0); }

    private static String effective(String epoch, int rate, double ratio) {
        return "{\"payment-svc\":{\"policyId\":\"payment-limits\",\"epoch\":\"" + epoch + "\",\"route\":\"\",\"spec\":\"{\\\"leakyBucket\\\":{\\\"ratePerSec\\\":"
                + rate + "},\\\"retry\\\":{\\\"budgetRatio\\\":" + ratio + "}}\"}}";
    }

    @Test void firstSyncSetsTheHealthyTargetFromTheRateLaterSyncsKeepIt() {
        effective = effective("e3", 120, .05);
        bridge.run();
        var first = workflow.current("payment-svc", Source.PROMETHEUS);
        assertEquals(120, first.bucketRate());
        assertEquals(120, first.healthyBucketTarget(), "first sync: healthy target = the current rate");

        // a cut to 60 (by a reviewer or another tool) becomes effective; the healthy target stays at 120
        effective = effective("e4", 60, .02);
        bridge.run();
        var second = workflow.current("payment-svc", Source.PROMETHEUS);
        assertEquals("e4", second.version());
        assertEquals(60, second.bucketRate(), "the current rate is updated");
        assertEquals(.02, second.retryRatio(), "the retry ratio is updated");
        assertEquals(120, second.healthyBucketTarget(), "the healthy target is not overwritten by the current rate");
    }
}
