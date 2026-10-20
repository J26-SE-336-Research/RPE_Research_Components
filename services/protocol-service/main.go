// Command protocol-service is the C1 protocol service (spec Appendix A3.7): the trail collector, the topology
// rebuilt from trails, per-request deadline attribution, the service registry, and header validation and conformance
// run live by both reference implementations (rpe-go in process, rpe-java in the container next to it).
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

type server struct {
	reg        *registry
	trails     *store
	truth      [][2]string
	vectorsDir string
	java       javaClient
}

func main() {
	reg := newRegistry(int64(envInt("REGISTRY_VERSION", 1)), strings.Split(env("REGISTRY",
		"gateway,order-svc,inventory-svc,payment-svc,loyalty-svc,fraud-check-svc,legacy-svc,shipping-svc"), ","))
	s := &server{
		reg:        reg,
		trails:     newStore(envInt("TRAIL_CAPACITY", 5000)),
		truth:      parseTruth(env("GROUND_TRUTH", "gateway>order-svc,order-svc>inventory-svc,order-svc>payment-svc,order-svc>loyalty-svc,order-svc>legacy-svc,legacy-svc>shipping-svc")),
		vectorsDir: env("VECTORS_DIR", "../../conformance-kit/vectors"),
		java:       javaClient{url: env("JAVA_URL", "http://127.0.0.1:8096"), client: &http.Client{Timeout: 10 * time.Second}},
	}
	srv := &http.Server{Addr: ":" + env("PORT", "8095"), Handler: s.routes(), ReadHeaderTimeout: 5 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		sd, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sd)
	}()
	log.Printf("protocol-service listening on %s", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("GET /api/protocol/registry", s.getRegistry)
	mux.HandleFunc("PUT /api/protocol/registry", s.putRegistry)
	mux.HandleFunc("POST /api/protocol/trails", s.postTrails)
	mux.HandleFunc("GET /api/protocol/trails", s.listTrails)
	mux.HandleFunc("GET /api/protocol/trails/{rid}", s.attribution)
	mux.HandleFunc("GET /api/protocol/attribution/{rid}", s.attribution)
	mux.HandleFunc("GET /api/protocol/topology", s.topology)
	mux.HandleFunc("GET /api/protocol/topology/compare", s.compare)
	mux.HandleFunc("POST /api/protocol/validate", s.validate)
	mux.HandleFunc("GET /api/protocol/conformance", s.conformance)
	return mux
}

func (s *server) getRegistry(w http.ResponseWriter, _ *http.Request) {
	v, ids := s.reg.snapshot()
	writeJSON(w, 200, map[string]any{"version": v, "services": ids})
}

func (s *server) putRegistry(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Services []string `json:"services"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	v, ids := s.reg.add(in.Services)
	writeJSON(w, 200, map[string]any{"version": v, "services": ids})
}

func (s *server) postTrails(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Trails []rpe.TrailRecord `json:"trails"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(&in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	accepted := 0
	for _, t := range in.Trails {
		if s.trails.add(t) {
			accepted++
		}
	}
	writeJSON(w, 200, map[string]int{"accepted": accepted, "rejected": len(in.Trails) - accepted})
}

func (s *server) window(r *http.Request) time.Duration {
	sec, err := strconv.Atoi(r.URL.Query().Get("window"))
	if err != nil || sec <= 0 || sec > 3600 {
		sec = 300
	}
	return time.Duration(sec) * time.Second
}

func (s *server) listTrails(w http.ResponseWriter, r *http.Request) {
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 || limit > 500 {
		limit = 50
	}
	route := r.URL.Query().Get("route")
	out := []TrailSummary{}
	for _, it := range s.trails.since(time.Now().Add(-s.window(r))) {
		if route != "" && it.rec.Route != route {
			continue
		}
		if len(out) == limit {
			break
		}
		out = append(out, summarize(it, s.reg))
	}
	received, rejected, held := s.trails.counts()
	writeJSON(w, 200, map[string]any{"trails": out, "received": received, "rejected": rejected, "held": held})
}

func (s *server) attribution(w http.ResponseWriter, r *http.Request) {
	it := s.trails.get(r.PathValue("rid"))
	if it == nil {
		writeErr(w, 404, "no trail for this request id (yet)")
		return
	}
	writeJSON(w, 200, attribute(it, s.reg))
}

func (s *server) topology(w http.ResponseWriter, r *http.Request) {
	window := s.window(r)
	items := s.trails.since(time.Now().Add(-window))
	if route := r.URL.Query().Get("route"); route != "" {
		kept := items[:0]
		for _, it := range items {
			if it.rec.Route == route {
				kept = append(kept, it)
			}
		}
		items = kept
	}
	writeJSON(w, 200, rebuild(items, s.reg, window))
}

func (s *server) compare(w http.ResponseWriter, r *http.Request) {
	window := s.window(r)
	t := rebuild(s.trails.since(time.Now().Add(-window)), s.reg, window)
	writeJSON(w, 200, map[string]any{"topology": t, "comparison": compare(t, s.truth)})
}

func (s *server) validate(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Kind  string `json:"kind"`
		Value string `json:"value"`
		Impl  string `json:"impl"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if in.Kind == "" {
		in.Kind = "context"
	}
	if !kinds[in.Kind] {
		writeErr(w, 400, "kind must be context, result, hop or trail")
		return
	}
	in.Value = strings.TrimSpace(in.Value)
	out := map[string]any{"kind": in.Kind}
	var g, j Verdict
	if in.Impl != "java" {
		g = validateGo(in.Kind, in.Value)
		out["go"] = g
	}
	if in.Impl != "go" {
		j = s.java.validate(r.Context(), in.Kind, in.Value)
		out["java"] = j
	}
	if in.Impl != "go" && in.Impl != "java" {
		out["agree"] = agree(g, j)
	}
	writeJSON(w, 200, out)
}

func (s *server) conformance(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, conformanceMatrix(r.Context(), s.vectorsDir, s.java))
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(k)); err == nil && v > 0 {
		return v
	}
	return def
}
