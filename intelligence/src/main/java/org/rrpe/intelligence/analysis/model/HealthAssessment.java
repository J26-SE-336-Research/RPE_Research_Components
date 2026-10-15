package org.rrpe.intelligence.analysis.model;

import java.time.Instant;
import java.util.List;

public record HealthAssessment(
        String serviceName,
        HealthStatus status,
        List<String> evidence,
        Instant assessedAt
) {
    public HealthAssessment {
        evidence = List.copyOf(evidence);
    }
}
