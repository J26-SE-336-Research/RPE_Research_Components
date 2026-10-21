package main

import (
	"bufio"
	"math"
	"sort"
	"strconv"
	"strings"
)

type sample struct {
	name   string
	labels map[string]string
	value  float64
}

// parseProm parses the Prometheus text exposition format (no exemplars, no timestamps needed).
func parseProm(text string) []sample {
	var out []sample
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		var name, labelPart, rest string
		if i := strings.IndexByte(line, '{'); i >= 0 {
			j := strings.LastIndexByte(line, '}')
			if j < i {
				continue
			}
			name, labelPart, rest = line[:i], line[i+1:j], strings.TrimSpace(line[j+1:])
		} else {
			f := strings.Fields(line)
			if len(f) < 2 {
				continue
			}
			name, rest = f[0], f[1]
		}
		v, err := strconv.ParseFloat(strings.Fields(rest)[0], 64)
		if err != nil {
			continue
		}
		out = append(out, sample{name, parseLabels(labelPart), v})
	}
	return out
}

func parseLabels(s string) map[string]string {
	labels := map[string]string{}
	for len(s) > 0 {
		eq := strings.IndexByte(s, '=')
		if eq < 0 || eq+1 >= len(s) || s[eq+1] != '"' {
			break
		}
		key := strings.TrimSpace(s[:eq])
		var b strings.Builder
		i := eq + 2
		for ; i < len(s); i++ {
			if s[i] == '\\' && i+1 < len(s) {
				b.WriteByte(s[i+1])
				i++
				continue
			}
			if s[i] == '"' {
				break
			}
			b.WriteByte(s[i])
		}
		labels[key] = b.String()
		s = strings.TrimLeft(s[min(i+1, len(s)):], ", ")
	}
	return labels
}

// SidecarStats summarises one sidecar's metrics for the panel.
type SidecarStats struct {
	Requests      map[string]map[string]float64 `json:"requests"` // direction -> outcome -> count
	P50Ms         map[string]float64            `json:"p50Ms"`
	P95Ms         map[string]float64            `json:"p95Ms"`
	OverheadAvgMs float64                       `json:"overheadAvgMs"`
	Inflight      float64                       `json:"inflight"`
	Backpressure  Backpressure                  `json:"backpressure"`
}

// Backpressure is the sidecar's backpressure state (C2): its inbound leaky bucket, the pressure signals it sent and
// received, and per destination the adaptive concurrency limit and the circuit breaker.
type Backpressure struct {
	InboundRatePerSec     float64 `json:"inboundRatePerSec"`
	QueueCapacity         float64 `json:"queueCapacity"`
	QueueDepth            float64 `json:"queueDepth"`
	QueueUtilization      float64 `json:"queueUtilizationPercent"`
	PressureActive        bool    `json:"pressureActive"`
	InboundRejected       float64 `json:"inboundRejected"`  // 503: inbound queue full
	OutboundRejected      float64 `json:"outboundRejected"` // fast-failed: breaker open
	SignalsReceived       float64 `json:"signalsReceived"`
	NotificationsSent     float64 `json:"notificationsSent"`
	NotificationsFailed   float64 `json:"notificationsFailed"`
	NotificationsRejected float64 `json:"notificationsRejected"`
	OutboundInFlight      float64 `json:"outboundInFlight"`
	OutboundQueued        float64 `json:"outboundQueued"`
	OpenBreakers          float64 `json:"openBreakers"`
	Peers                 []Peer  `json:"peers"`
}

// Peer is one destination this sidecar calls (host:port).
type Peer struct {
	Peer                string  `json:"peer"`
	ConcurrencyLimit    float64 `json:"concurrencyLimit"`
	HealthyLimit        float64 `json:"healthyLimit"`
	InFlight            float64 `json:"inFlight"`
	Queued              float64 `json:"queued"`
	PressureActive      bool    `json:"pressureActive"`
	ReportedUtilization float64 `json:"reportedUtilizationPercent"` // -1 when no pressure is reported
	Breaker             string  `json:"breaker"`                    // closed | open | half-open
}

// backpressureGauges maps the sidecar's aggregate backpressure metrics to their fields.
var backpressureGauges = map[string]func(*Backpressure, float64){
	"rpe_backpressure_inbound_rate_per_second":                func(b *Backpressure, v float64) { b.InboundRatePerSec = v },
	"rpe_backpressure_inbound_queue_capacity":                 func(b *Backpressure, v float64) { b.QueueCapacity = v },
	"rpe_backpressure_inbound_queue_depth":                    func(b *Backpressure, v float64) { b.QueueDepth = v },
	"rpe_backpressure_inbound_queue_utilization_percent":      func(b *Backpressure, v float64) { b.QueueUtilization = v },
	"rpe_backpressure_inbound_pressure_active":                func(b *Backpressure, v float64) { b.PressureActive = v > 0 },
	"rpe_backpressure_inbound_rejected_total":                 func(b *Backpressure, v float64) { b.InboundRejected = v },
	"rpe_backpressure_outbound_rejected_total":                func(b *Backpressure, v float64) { b.OutboundRejected = v },
	"rpe_backpressure_peer_pressure_signals_total":            func(b *Backpressure, v float64) { b.SignalsReceived = v },
	"rpe_backpressure_peer_pressure_notifications_sent_total": func(b *Backpressure, v float64) { b.NotificationsSent = v },
	"rpe_backpressure_peer_pressure_notifications_failed_total": func(b *Backpressure, v float64) {
		b.NotificationsFailed = v
	},
	"rpe_backpressure_peer_pressure_notifications_rejected_total": func(b *Backpressure, v float64) {
		b.NotificationsRejected = v
	},
	"rpe_backpressure_outbound_in_flight": func(b *Backpressure, v float64) { b.OutboundInFlight = v },
	"rpe_backpressure_outbound_queued":    func(b *Backpressure, v float64) { b.OutboundQueued = v },
	"rpe_circuit_breaker_open_peers":      func(b *Backpressure, v float64) { b.OpenBreakers = v },
}

// peerGauges maps the sidecar's per-destination metrics (label peer) to their fields.
var peerGauges = map[string]func(*Peer, float64){
	"rpe_backpressure_peer_concurrency_limit":            func(p *Peer, v float64) { p.ConcurrencyLimit = v },
	"rpe_backpressure_peer_healthy_concurrency_limit":    func(p *Peer, v float64) { p.HealthyLimit = v },
	"rpe_backpressure_peer_in_flight":                    func(p *Peer, v float64) { p.InFlight = v },
	"rpe_backpressure_peer_queued":                       func(p *Peer, v float64) { p.Queued = v },
	"rpe_backpressure_peer_pressure_active":              func(p *Peer, v float64) { p.PressureActive = v > 0 },
	"rpe_backpressure_peer_reported_utilization_percent": func(p *Peer, v float64) { p.ReportedUtilization = v },
	"rpe_circuit_breaker_peer_state":                     func(p *Peer, v float64) { p.Breaker = breakerState(v) },
}

func breakerState(v float64) string {
	switch v {
	case 1:
		return "open"
	case 2:
		return "half-open"
	}
	return "closed"
}

func summarize(samples []sample) SidecarStats {
	st := SidecarStats{Requests: map[string]map[string]float64{}, P50Ms: map[string]float64{}, P95Ms: map[string]float64{}}
	buckets := map[string][][2]float64{}
	var ohSum, ohCount float64
	peers := map[string]*Peer{}
	for _, s := range samples {
		if set, ok := backpressureGauges[s.name]; ok {
			set(&st.Backpressure, s.value)
			continue
		}
		if set, ok := peerGauges[s.name]; ok && s.labels["peer"] != "" {
			p := peers[s.labels["peer"]]
			if p == nil {
				p = &Peer{Peer: s.labels["peer"], ReportedUtilization: -1, Breaker: "closed"}
				peers[p.Peer] = p
			}
			set(p, s.value)
			continue
		}
		switch s.name {
		case "rpe_requests_total":
			d := s.labels["direction"]
			if st.Requests[d] == nil {
				st.Requests[d] = map[string]float64{}
			}
			st.Requests[d][s.labels["outcome"]] += s.value
		case "rpe_request_duration_seconds_bucket":
			le := s.labels["le"]
			bound := math.Inf(1)
			if le != "+Inf" {
				bound, _ = strconv.ParseFloat(le, 64)
			}
			buckets[s.labels["direction"]] = append(buckets[s.labels["direction"]], [2]float64{bound, s.value})
		case "rpe_proxy_overhead_seconds_sum":
			ohSum = s.value
		case "rpe_proxy_overhead_seconds_count":
			ohCount = s.value
		case "rpe_inflight_requests":
			st.Inflight = s.value
		}
	}
	for d, b := range buckets {
		st.P50Ms[d] = quantile(b, 0.5) * 1000
		st.P95Ms[d] = quantile(b, 0.95) * 1000
	}
	if ohCount > 0 {
		st.OverheadAvgMs = ohSum / ohCount * 1000
	}
	st.Backpressure.Peers = make([]Peer, 0, len(peers))
	for _, p := range peers {
		st.Backpressure.Peers = append(st.Backpressure.Peers, *p)
	}
	sort.Slice(st.Backpressure.Peers, func(i, j int) bool { return st.Backpressure.Peers[i].Peer < st.Backpressure.Peers[j].Peer })
	return st
}

// quantile estimates a quantile from cumulative histogram buckets (upper bound, like histogram_quantile).
func quantile(b [][2]float64, q float64) float64 {
	sort.Slice(b, func(i, j int) bool { return b[i][0] < b[j][0] })
	if len(b) == 0 || b[len(b)-1][1] == 0 {
		return 0
	}
	target := q * b[len(b)-1][1]
	prevBound, prevCount := 0.0, 0.0
	for _, x := range b {
		if x[1] >= target {
			if math.IsInf(x[0], 1) {
				return prevBound
			}
			if x[1] == prevCount {
				return x[0]
			}
			return prevBound + (x[0]-prevBound)*(target-prevCount)/(x[1]-prevCount)
		}
		prevBound, prevCount = x[0], x[1]
	}
	return prevBound
}
