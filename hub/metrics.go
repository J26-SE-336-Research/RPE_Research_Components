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
}

func summarize(samples []sample) SidecarStats {
	st := SidecarStats{Requests: map[string]map[string]float64{}, P50Ms: map[string]float64{}, P95Ms: map[string]float64{}}
	buckets := map[string][][2]float64{}
	var ohSum, ohCount float64
	for _, s := range samples {
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
