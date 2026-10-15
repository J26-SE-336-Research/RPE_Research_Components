package org.rrpe.intelligence.analysis.api;

import org.rrpe.intelligence.analysis.model.ServiceMetrics;

public record ClassificationRequest(
        ServiceMetrics recent,
        ServiceMetrics baseline
) {
}
