package main

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// C1 protocol service: trails, topology, deadline per hop and Java vs Go

// relayProtocol forwards a request to the C1 protocol service and returns its answer unchanged.
func (h *hub) relayProtocol(w http.ResponseWriter, r *http.Request, method, path string, body any) {
	code, _, b, _, err := h.call(r.Context(), method, h.cfg.protocolURL+path, body)
	if err != nil {
		writeErr(w, 502, "protocol service unavailable: "+err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

// query keeps only the listed query parameters.
func query(r *http.Request, keys ...string) string {
	q := url.Values{}
	for _, k := range keys {
		if v := r.URL.Query().Get(k); v != "" {
			q.Set(k, v)
		}
	}
	if len(q) == 0 {
		return ""
	}
	return "?" + q.Encode()
}

// compareImpls checks one header value with rpe-go and rpe-java and shows both answers.
func (h *hub) compareImpls(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Kind  string `json:"kind"`
		Value string `json:"value"`
	}
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	h.relayProtocol(w, r, http.MethodPost, "/api/protocol/validate", map[string]string{"kind": in.Kind, "value": in.Value, "impl": "both"})
}

func (h *hub) conformance(w http.ResponseWriter, r *http.Request) {
	h.relayProtocol(w, r, http.MethodGet, "/api/protocol/conformance", nil)
}

func (h *hub) topology(w http.ResponseWriter, r *http.Request) {
	h.relayProtocol(w, r, http.MethodGet, "/api/protocol/topology/compare"+query(r, "window"), nil)
}

func (h *hub) trails(w http.ResponseWriter, r *http.Request) {
	h.relayProtocol(w, r, http.MethodGet, "/api/protocol/trails"+query(r, "limit", "window", "route"), nil)
}

func (h *hub) attribution(w http.ResponseWriter, r *http.Request) {
	h.relayProtocol(w, r, http.MethodGet, "/api/protocol/attribution/"+url.PathEscape(r.PathValue("rid")), nil)
}

func (h *hub) registry(w http.ResponseWriter, r *http.Request) {
	h.relayProtocol(w, r, http.MethodGet, "/api/protocol/registry", nil)
}

// rootRID is the request id of a chain, taken from the gateway's context.
func rootRID(hops []Hop) string {
	if len(hops) > 0 {
		return hops[0].Fields["rid"]
	}
	return ""
}

// requestCounters reads a service's side effect counters for one request.
func (h *hub) requestCounters(r *http.Request, service, rid string) map[string]int64 {
	out := map[string]int64{}
	if rid == "" {
		return out
	}
	if _, _, b, _, err := h.call(r.Context(), http.MethodGet, svcURL(service, "/admin/counters?rid="+url.QueryEscape(rid)), nil); err == nil {
		_ = json.Unmarshal(b, &out)
	}
	return out
}

// demoRetry is the retry safety demo. Payment is made slower than the chain budget, and order-svc gives up on an
// attempt after attemptTimeoutMs and retries once. With a plain retry the first attempt still finishes at payment
// and the retry charges the card a second time. With the RPE retry gate the retry is refused because there is no
// time left for it, so the card is charged once. Counters are read for this request id only, so other traffic
// doesn't change the result.
func (h *hub) demoRetry(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Mode             string `json:"mode"`
		LatencyMs        int    `json:"latencyMs"`
		AttemptTimeoutMs int    `json:"attemptTimeoutMs"`
	}
	_ = decode(r, &in)
	if in.Mode != "plain" && in.Mode != "rpe" {
		writeErr(w, 400, "mode must be plain or rpe")
		return
	}
	in.LatencyMs = clamp(in.LatencyMs, 100, 10000, 1200)
	in.AttemptTimeoutMs = clamp(in.AttemptTimeoutMs, 50, 5000, 600)
	retry := map[string]any{"mode": in.Mode, "child": "payment", "attemptTimeoutMs": in.AttemptTimeoutMs, "maxAttempts": 2, "backoffMs": 50}
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("payment-svc", "/admin/faults"), map[string]any{"latencyMs": in.LatencyMs})
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("order-svc", "/admin/retry"), retry)
	res := h.runChain(r.Context(), "/api/orders")
	// every attempt that was sent still finishes at payment, even after the caller gave up on it
	time.Sleep(time.Duration(in.LatencyMs+400) * time.Millisecond)
	payment := h.requestCounters(r, "payment-svc", res.RID)
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("order-svc", "/admin/retry"), map[string]any{"mode": "off"})
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("payment-svc", "/admin/faults"), map[string]any{})
	attempts, refused := 0, ""
	for _, hop := range res.Hops {
		if hop.Name == "payment" {
			attempts, refused = hop.Attempts, hop.RetryRefused
		}
	}
	writeJSON(w, 200, map[string]any{"mode": in.Mode, "latencyMs": in.LatencyMs, "attemptTimeoutMs": in.AttemptTimeoutMs,
		"chain": res, "rid": res.RID, "payment": payment, "cardsCharged": payment["cards_charged"],
		"attempts": attempts, "retryRefused": refused, "rule": ruleOf(refused)})
}

func ruleOf(refused string) string {
	if refused == "" {
		return ""
	}
	return "R-R02"
}

// signalSummary fills the "Retryable" and "Payment work" boxes of the slow payment demo. The late request is still
// served, the chain is marked cs=expired so it can't be retried, and RPE doesn't stop payment's work, only the app
// could decide that.
func signalSummary(raw string) map[string]any {
	cs := "none"
	terminal := false
	for _, part := range strings.Split(raw, ",") {
		k, v, _ := strings.Cut(strings.TrimSpace(part), "=")
		switch k {
		case "cs":
			cs = v
		case "term":
			terminal = v == "?1"
		}
	}
	retryable := cs == "none" && !terminal
	return map[string]any{"cs": cs, "terminal": terminal, "retryable": retryable,
		"rule": "R-R02: a cancelled or expired chain is never retried; the original request was served (signal, never reject)"}
}
