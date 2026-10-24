package main

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ---------- C1 Protocol: the protocol service (trail, topology, attribution, Java vs Go) ----------

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

// compareImpls validates one header value with rpe-go and rpe-java side by side.
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

// rootRID is the request id of a chain (from the gateway's context).
func rootRID(hops []Hop) string {
	if len(hops) > 0 {
		return hops[0].Fields["rid"]
	}
	return ""
}

// requestCounters reads a service's side-effect counters of one request.
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

// demoRetry: retry safety (R-R02). Payment is made slower than the chain budget and order-svc gives up on an attempt
// after attemptTimeoutMs, then retries once. With a plain retry the abandoned attempt still completes on the payment
// side and the retry charges the card again; with the same policy through the RPE retry gate the retry is refused,
// because the chain cannot finish another attempt in time, and the card is charged once. Counters are read for this
// request only (by request id), so background traffic does not affect the result.
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
	res := h.runChain(r.Context(), http.MethodGet, "/api/orders")
	// every attempt that was sent completes on the payment side, also after its caller gave up
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

// signalSummary describes the restored "Retryable / terminal" and "Payment work" views of the slow-dependency demo
// in signal mode: the late original request is served (never rejected), the chain is marked cs=expired, which makes it
// non-retryable, and payment's work is not abandoned by RPE: only the application can decide to stop it.
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
