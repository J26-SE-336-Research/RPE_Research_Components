package org.rrpe.intelligence.analysis.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.rrpe.intelligence.analysis.model.HealthAssessment;
import org.rrpe.intelligence.analysis.model.HealthStatus;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.springframework.stereotype.Service;

@Service
public class ServiceHealthClassifier {

    public HealthAssessment classify(
            ServiceMetrics recent,
            ServiceMetrics baseline
    ) {
        List<String> evidence = new ArrayList<>();

        double latencyRatio = calculateRatio(
                recent.p95LatencyMs(),
                baseline.p95LatencyMs()
        );

        HealthStatus status;

        if (latencyRatio >= 2.0 || recent.errorRate() >= 0.10) {
            status = HealthStatus.OVERLOADED;
        } else if (latencyRatio >= 1.5 || recent.errorRate() >= 0.05) {
            status = HealthStatus.STRESSED;
        } else {
            status = HealthStatus.HEALTHY;
        }

        evidence.add(String.format(
                "Recent latency is %.2f times the baseline",
                latencyRatio
        ));

        evidence.add(String.format(
                "Recent error rate is %.2f%%",
                recent.errorRate() * 100
        ));

        return new HealthAssessment(
                recent.serviceName(),
                status,
                evidence,
                Instant.now()
        );
    }

    private double calculateRatio(double recent, double baseline) {
        if (baseline <= 0) {
            return recent > 0 ? Double.POSITIVE_INFINITY : 1.0;
        }

        return recent / baseline;
    }
}
