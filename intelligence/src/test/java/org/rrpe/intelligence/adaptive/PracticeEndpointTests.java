package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /demo/{scenario}?to=queue → practice cards in the status list; review them; never in the outbox; clear removes them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false", "rrpe.connections.collect=false", "rrpe.mock-enabled=true", "rrpe.control-token=test-secret"})
class PracticeEndpointTests {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    HttpResponse<String> call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/intelligence" + path)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode practice() throws Exception {
        var out = json.createArrayNode();
        json.readTree(call("GET", "", null).body()).get("changes").forEach(c -> { if ("MOCK".equals(c.get("proposal").get("source").asString())) out.add(c); });
        return out;
    }

    @Test void practiceCardsCanBeReviewedAreNeverDeliveredAndCanBeCleared() throws Exception {
        var plain = json.readTree(call("POST", "/demo/joint-recovery", null).body());
        assertFalse(plain.has("practiceCards"), "without ?to=queue nothing changes");
        assertEquals(0, practice().size());
        var r = json.readTree(call("POST", "/demo/joint-recovery?to=queue", null).body());
        assertEquals(2, r.get("practiceCards").size(), "one joint card: rate and deadline");
        assertEquals(2, practice().size());
        var status = json.readTree(call("GET", "", null).body());
        assertEquals(1, status.get("joints").size(), "grouped as one joint card");
        String id = practice().get(0).get("proposal").get("id").asString();
        assertEquals(200, call("POST", "/changes/" + id + "/review", "{\"reviewer\":\"sre-alice\",\"approve\":true,\"comment\":\"practice\"}").statusCode());
        practice().forEach(c -> {
            assertEquals("APPROVED", c.get("decision").asString(), "one decision for both parts");
            assertEquals("sre-alice", c.get("reviewer").asString());
        });
        assertEquals("[]", call("GET", "/outbox", null).body(), "never delivered");
        assertEquals(400, call("POST", "/demo/joint-recovery?to=elsewhere", null).statusCode());
        var cleared = json.readTree(call("POST", "/practice/clear", null).body());
        assertEquals(2, cleared.get("removed").asInt());
        assertEquals(0, practice().size());
    }
}
