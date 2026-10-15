package org.rrpe.intelligence.analysis.api;

import java.time.Instant;
import java.util.List;

/**
 * Chain-scoped cancellation evidence for one recent analysis window.
 * The caller must obtain these measurements from correlated request/trace
 * telemetry. A service's aggregate cancellationCount alone cannot supply them.
 * This model does not collect traces or perform cancellation analysis.
 *
 * @param chainId identifier of the affected request chain
 * @param services service names in request-path order
 * @param windowStart start of the measurement window
 * @param windowEnd end of the measurement window
 * @param cancellationCount distinct cancelled requests observed in the window
 * @param continuedProcessingCount distinct cancelled requests with processing
 *        still active after cancellation plus observationGraceMs
 * @param downstreamActivityCount distinct cancelled requests that started a
 *        new downstream call after cancellation plus observationGraceMs
 * @param observationGraceMs grace period used by the telemetry producer to
 *        distinguish propagation delay from continued unnecessary work;
 *        this is measurement metadata, not a recommended policy value
 * @param telemetryComplete whether the producer has the correlated telemetry
 *        needed to judge post-cancellation activity; missing evidence must not
 *        be represented as a confirmed zero
 */
public record CancellationAnalysisRequest(
        String chainId,
        List<String> services,
        Instant windowStart,
        Instant windowEnd,
        long cancellationCount,
        long continuedProcessingCount,
        long downstreamActivityCount,
        long observationGraceMs,
        boolean telemetryComplete
) {
    public CancellationAnalysisRequest {
        if (services != null) {
            services = List.copyOf(services);
        }
    }
}
