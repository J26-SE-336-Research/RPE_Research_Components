// Command rpe-admission-webhook is the RPE Mutating Admission Webhook (C3). It injects the RPE
// sidecar and its traffic-redirection init container into Pods of namespaces labelled
// rpe-injection=enabled.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type admissionReview struct {
	APIVersion string             `json:"apiVersion"`
	Kind       string             `json:"kind"`
	Request    *admissionRequest  `json:"request,omitempty"`
	Response   *admissionResponse `json:"response,omitempty"`
}

type admissionRequest struct {
	UID       string                `json:"uid"`
	Kind      struct{ Kind string } `json:"kind"`
	Operation string                `json:"operation"`
	Object    json.RawMessage       `json:"object"`
}

type admissionResponse struct {
	UID       string  `json:"uid"`
	Allowed   bool    `json:"allowed"`
	Status    *status `json:"status,omitempty"`
	Patch     []byte  `json:"patch,omitempty"`
	PatchType *string `json:"patchType,omitempty"`
}

type status struct {
	Message string `json:"message"`
	Code    int32  `json:"code,omitempty"`
}

func main() {
	cfg := config{
		sidecarImage:          env("SIDECAR_IMAGE", "rpe/sidecar:dev"),
		sidecarPullPolicy:     env("SIDECAR_IMAGE_PULL_POLICY", "IfNotPresent"),
		defaultOutboundPorts:  env("DEFAULT_OUTBOUND_PORTS", "80,8080"),
		defaultInboundMode:    env("DEFAULT_INBOUND_MODE", "redirect"),
		defaultDeadlineMs:     env("DEFAULT_DEADLINE_MS", "1000"),
		defaultMissingContext: env("DEFAULT_MISSING_CONTEXT", "passthrough"),
		policyAPIURL:          env("POLICY_API_URL", ""),
		hubURL:                env("HUB_URL", ""),
		otelEndpoint:          env("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		certFile:              env("TLS_CERT_FILE", "/tls/tls.crt"),
		keyFile:               env("TLS_KEY_FILE", "/tls/tls.key"),
		port:                  env("WEBHOOK_PORT", "8443"),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/mutate", func(w http.ResponseWriter, r *http.Request) { handleAdmission(w, r, cfg) })
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	server := &http.Server{
		Addr:              ":" + cfg.port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12},
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Printf("rpe-admission-webhook listening on %s (sidecar image %s, inbound mode %s)", server.Addr, cfg.sidecarImage, cfg.defaultInboundMode)
	if err := server.ListenAndServeTLS(cfg.certFile, cfg.keyFile); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("webhook server stopped: %v", err)
	}
}

func handleAdmission(w http.ResponseWriter, r *http.Request, cfg config) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST required", http.StatusMethodNotAllowed)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2<<20)
	var review admissionReview
	if err := json.NewDecoder(r.Body).Decode(&review); err != nil || review.Request == nil || review.Request.UID == "" {
		http.Error(w, "invalid AdmissionReview", http.StatusBadRequest)
		return
	}
	resp := &admissionResponse{UID: review.Request.UID, Allowed: true}
	if review.Request.Operation == "CREATE" && review.Request.Kind.Kind == "Pod" {
		patch, err := mutatePod(review.Request.Object, cfg)
		switch {
		case err != nil:
			resp.Allowed = false
			resp.Status = &status{Message: "rpe injection: " + err.Error(), Code: http.StatusBadRequest}
		case len(patch) > 0:
			b, _ := json.Marshal(patch)
			pt := "JSONPatch"
			resp.Patch, resp.PatchType = b, &pt
			log.Printf("injected rpe sidecar into pod %s", podRef(review.Request.Object))
		}
	}
	review.Response, review.Request = resp, nil
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(review)
}

func podRef(raw json.RawMessage) string {
	var p pod
	_ = json.Unmarshal(raw, &p)
	name := p.Metadata.Name
	if name == "" {
		name = p.Metadata.GenerateName + "*"
	}
	return p.Metadata.Namespace + "/" + name
}

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
