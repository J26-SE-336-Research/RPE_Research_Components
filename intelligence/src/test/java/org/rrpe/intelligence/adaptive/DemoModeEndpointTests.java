package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/** POST /demo/{scenario}?mode=auto: isolated automatic approval for the rate scenarios; without it, unchanged. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false", "rrpe.connections.collect=false", "rrpe.mock-enabled=true", "rrpe.control-token=test-secret"})
class DemoModeEndpointTests {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    HttpResponse<String> post(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void modeAutoShowsC4AutoAndTheDefaultIsUnchanged() throws Exception {
        var auto = json.readTree(post("/api/v2/intelligence/demo/rate-too-low?mode=auto").body());
        assertEquals("auto", auto.get("mode").asString());
        var steps = auto.get("rateEvidence").get(0).get("steps");
        assertTrue(steps.size() > 0);
        steps.forEach(s -> assertEquals("c4-auto", s.get("reviewer").asString()));
        var normal = json.readTree(post("/api/v2/intelligence/demo/rate-too-low").body());
        assertFalse(normal.has("mode"));
        normal.get("rateEvidence").get(0).get("steps").forEach(s -> assertEquals("simulated-reviewer", s.get("reviewer").asString()));
        assertEquals(400, post("/api/v2/intelligence/demo/rate-too-low?mode=fast").statusCode());
        var outbox = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/intelligence/outbox")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("[]", outbox.body(), "never delivered");
    }
}
