package main

import (
	"github.com/prometheus/client_golang/prometheus"
	"sigs.k8s.io/controller-runtime/pkg/metrics"
)

// Operator metrics (contract §8: coverage, drift repairs, rollout status), served by the controller-runtime
// metrics server and scraped by rpe-prometheus.
var (
	coverageRatio = prometheus.NewGauge(prometheus.GaugeOpts{Name: "rpe_injection_coverage_ratio",
		Help: "Share of eligible pods in RPE-enabled namespaces that run the RPE sidecar (coverage audit)."})
	coverageGaps = prometheus.NewGauge(prometheus.GaugeOpts{Name: "rpe_injection_gap_pods",
		Help: "Eligible pods in RPE-enabled namespaces without the RPE sidecar."})
	driftRepairs = prometheus.NewCounter(prometheus.CounterOpts{Name: "rpe_drift_repairs_total",
		Help: "Proxies whose policy differed from the approved one and was re-delivered."})
	mixedRequests = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "rpe_mixed_policy_requests_total",
		Help: "Requests served under another version of a policy than the epoch they carry, during rollouts, by mode."}, []string{"mode"})
	rolloutConvergence = prometheus.NewHistogramVec(prometheus.HistogramOpts{Name: "rpe_rollout_convergence_seconds",
		Help:    "Time from rollout start until every target proxy enforces the new version, by mode.",
		Buckets: []float64{0.25, 0.5, 1, 2, 3, 5, 8, 13, 21, 34}}, []string{"mode"})
	rolloutsFinished = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "rpe_rollouts_total",
		Help: "Finished rollouts by mode and final phase."}, []string{"mode", "phase"})
)

func init() {
	metrics.Registry.MustRegister(coverageRatio, coverageGaps, driftRepairs, mixedRequests, rolloutConvergence, rolloutsFinished)
}
