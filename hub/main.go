// Command rpe-hub is the backend of the RPE Admin Panel. It authenticates users (admin, sre, viewer),
// exposes /api/hub/* endpoints that act on the REAL running ecosystem (Kubernetes API, demo services,
// sidecar control/metrics endpoints, PostgreSQL policy store, intelligence layer) and serves the console SPA.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

type config struct {
	port            string
	staticDir       string
	demoNamespace   string
	systemNamespace string
	gatewayURL      string
	intelligenceURL string
	policyAPIURL    string
	protocolURL     string
	intelToken      string
	users           string
	sessionSecret   string
}

func loadConfig() config {
	return config{
		port:            env("PORT", "8080"),
		staticDir:       env("STATIC_DIR", "/console"),
		demoNamespace:   env("DEMO_NAMESPACE", "rpe-demo"),
		systemNamespace: env("SYSTEM_NAMESPACE", "rpe-system"),
		gatewayURL:      env("GATEWAY_URL", "http://gateway.rpe-demo:8080"),
		intelligenceURL: env("INTELLIGENCE_URL", "http://rpe-intelligence.rpe-system:8090"),
		policyAPIURL:    env("POLICY_API_URL", "http://rpe-policy-api.rpe-system"),
		protocolURL:     env("PROTOCOL_URL", "http://rpe-protocol.rpe-system:8095"),
		intelToken:      env("INTEL_CONTROL_TOKEN", ""),
		users:           env("HUB_USERS", "admin:admin:admin,sre:sre:sre,viewer:viewer:viewer"),
		sessionSecret:   env("SESSION_SECRET", "rpe-dev-session-secret"),
	}
}

func main() {
	cfg := loadConfig()
	h, err := newHub(cfg)
	if err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{Addr: ":" + cfg.port, Handler: h.routes(), ReadHeaderTimeout: 10 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		sd, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(sd)
	}()
	log.Printf("rpe-hub listening on %s (console from %s)", srv.Addr, cfg.staticDir)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

// spa serves the built console and falls back to index.html for client-side routes.
func spa(dir string) http.Handler {
	fs := http.FileServer(http.Dir(dir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := filepath.Join(dir, filepath.Clean("/"+r.URL.Path))
		if st, err := os.Stat(p); err != nil || st.IsDir() {
			if !strings.HasPrefix(r.URL.Path, "/assets/") {
				http.ServeFile(w, r, filepath.Join(dir, "index.html"))
				return
			}
		}
		fs.ServeHTTP(w, r)
	})
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
