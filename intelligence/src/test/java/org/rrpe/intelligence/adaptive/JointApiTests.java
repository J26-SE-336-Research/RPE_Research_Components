package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Joint recalibration in the API, with the demo setting rrpe.adaptive.joint.enabled=true (as in intelligence.yaml). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false", "rrpe.connections.collect=false", "rrpe.mock-enabled=true", "rrpe.control-token=test-secret",
                "rrpe.adaptive.joint.enabled=true"})
class JointApiTests {
    @LocalServerPort int port;
    @Autowired AdaptiveConfig.JointSettings joint;
    private final JsonMapper json = JsonMapper.builder().build();
    JsonNode request(String path, String method) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (method.equals("GET")) request.GET(); else request.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return json.readTree(response.body());
    }

    @Test void theSettingBindsAndTheStatusHasAnAdditiveJointsField() throws Exception {
        assertTrue(joint.enabled());
        var status = request("/api/v2/intelligence", "GET");
        for (String field : List.of("collection", "assessments", "changes", "mockEnabled", "deadlineEvidence", "rateEvidence")) assertTrue(status.has(field), field);
        assertTrue(status.get("joints").isArray());
        assertEquals(0, status.get("joints").size(), "no live joint card");
    }
}
