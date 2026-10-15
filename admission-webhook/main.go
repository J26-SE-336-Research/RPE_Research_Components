package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	annotationPrefix = "resilience.rrpe.dev/"
	annotationInject = annotationPrefix + "inject"
	annotationRole   = annotationPrefix + "role"
	annotationIn     = annotationPrefix + "inbound-ports"
	annotationOut    = annotationPrefix + "outbound-ports"
	annotationExempt = annotationPrefix + "excluded-outbound-ports"
	annotationProxy  = annotationPrefix + "proxy-port"
	annotationDelay  = annotationPrefix + "default-deadline-ms"
	annotationDone   = annotationPrefix + "injected"
	proxyName        = "netty-proxy"
	initName         = "configure-proxy-routing"
	metricsPort      = 9090
)

type config struct {
	proxyImage      string
	proxyImagePull  string
	contextTTL      string
	defaultDeadline string
	certFile        string
	keyFile         string
	port            string
}

type admissionReview struct {
	APIVersion string              `json:"apiVersion"`
	Kind       string              `json:"kind"`
	Request    *admissionRequest   `json:"request,omitempty"`
	Response   *admissionResponse  `json:"response,omitempty"`
}

type admissionRequest struct {
	UID       string          `json:"uid"`
	Kind      groupVersionKind `json:"kind"`
	Operation string          `json:"operation"`
	Object    json.RawMessage `json:"object"`
}

type groupVersionKind struct {
	Group   string `json:"group"`
	Version string `json:"version"`
	Kind    string `json:"kind"`
}

type admissionResponse struct {
	UID       string          `json:"uid"`
	Allowed   bool            `json:"allowed"`
	Status    *status         `json:"status,omitempty"`
	Patch     []byte          `json:"patch,omitempty"`
	PatchType *string         `json:"patchType,omitempty"`
}

type status struct {
	Message string `json:"message"`
	Code    int32  `json:"code,omitempty"`
}

type pod struct {
	Metadata struct {
		Name        string            `json:"name"`
		Namespace   string            `json:"namespace"`
		Annotations map[string]string `json:"annotations"`
	} `json:"metadata"`
	Spec struct {
		Containers     []struct { Name string `json:"name"` } `json:"containers"`
		InitContainers []struct { Name string `json:"name"` } `json:"initContainers"`
	} `json:"spec"`
}

type portRange struct{ first, last int }

type patchOperation struct {
	Op    string `json:"op"`
	Path  string `json:"path"`
	Value any    `json:"value"`
}

func main() {
	cfg := config{
		proxyImage:      env("PROXY_IMAGE", "2002rusira/netty-sidecar-proxy-rpe-implementation:v9"),
		proxyImagePull:  env("PROXY_IMAGE_PULL_POLICY", "IfNotPresent"),
		contextTTL:      env("REQUEST_CONTEXT_TTL_MILLIS", "20000"),
		defaultDeadline: env("DEFAULT_DEADLINE_MILLIS", "5000"),
		certFile:        env("TLS_CERT_FILE", "/tls/tls.crt"),
		keyFile:         env("TLS_KEY_FILE", "/tls/tls.key"),
		port:            env("WEBHOOK_PORT", "8443"),
	}
	port, err := strconv.Atoi(cfg.port)
	if err != nil || port < 1 || port > 65535 {
		log.Fatalf("WEBHOOK_PORT must be an integer between 1 and 65535")
	}
	ttl, err := strconv.Atoi(cfg.contextTTL)
	if err != nil || ttl < 1 {
		log.Fatalf("REQUEST_CONTEXT_TTL_MILLIS must be a positive integer")
	}
	deadline, err := strconv.Atoi(cfg.defaultDeadline)
	if err != nil || deadline < 1 {
		log.Fatalf("DEFAULT_DEADLINE_MILLIS must be a positive integer")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/mutate", func(w http.ResponseWriter, r *http.Request) {
		handleAdmission(w, r, cfg)
	})
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "ok\n")
	})

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
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	log.Printf("RRPE admission webhook listening on %s", server.Addr)
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
	defer r.Body.Close()
	var review admissionReview
	if err := json.NewDecoder(r.Body).Decode(&review); err != nil {
		http.Error(w, "invalid AdmissionReview JSON", http.StatusBadRequest)
		return
	}
	if review.Request == nil || review.Request.UID == "" {
		http.Error(w, "AdmissionReview request and UID are required", http.StatusBadRequest)
		return
	}
	response := &admissionResponse{UID: review.Request.UID, Allowed: true}
	if review.Request.Operation == "CREATE" && review.Request.Kind.Kind == "Pod" {
		patch, err := mutatePod(review.Request.Object, cfg)
		if err != nil {
			response.Allowed = false
			response.Status = &status{Message: err.Error(), Code: http.StatusBadRequest}
		} else if len(patch) > 0 {
			patchBytes, marshalErr := json.Marshal(patch)
			if marshalErr != nil {
				response.Allowed = false
				response.Status = &status{Message: "failed to encode Pod mutation", Code: http.StatusInternalServerError}
			} else {
				patchType := "JSONPatch"
				response.Patch = patchBytes
				response.PatchType = &patchType
			}
		}
	}
	review.Response = response
	review.Request = nil
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(review); err != nil {
		log.Printf("failed to write AdmissionReview response: %v", err)
	}
}

func mutatePod(raw json.RawMessage, cfg config) ([]patchOperation, error) {
	var p pod
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("invalid Pod object: %w", err)
	}
	annotations := p.Metadata.Annotations
	if annotations[annotationInject] != "enabled" {
		return nil, nil
	}
	if annotations[annotationDone] == "true" {
		return nil, nil
	}
	for _, c := range p.Spec.Containers {
		if c.Name == proxyName {
			return nil, fmt.Errorf("Pod opts in to RRPE injection but already has a %q container", proxyName)
		}
	}
	for _, c := range p.Spec.InitContainers {
		if c.Name == initName {
			return nil, fmt.Errorf("Pod opts in to RRPE injection but already has a %q init container", initName)
		}
	}

	role := annotations[annotationRole]
	if role != "gateway" && role != "service" {
		return nil, fmt.Errorf("%s must be either gateway or service", annotationRole)
	}
	inbound, err := parsePorts(annotations[annotationIn], true)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", annotationIn, err)
	}
	proxyPort, err := parseSinglePort(annotations[annotationProxy])
	if err != nil {
		return nil, fmt.Errorf("%s: %w", annotationProxy, err)
	}
	if proxyPort == metricsPort {
		return nil, fmt.Errorf("%s cannot use reserved metrics port %d", annotationProxy, metricsPort)
	}
	for _, pr := range inbound {
		if proxyPort >= pr.first && proxyPort <= pr.last {
			return nil, fmt.Errorf("%s must not overlap %s", annotationProxy, annotationIn)
		}
	}
	if _, err := parsePorts(annotations[annotationOut], false); err != nil {
		return nil, fmt.Errorf("%s: %w", annotationOut, err)
	}
	if _, err := parsePorts(annotations[annotationExempt], false); err != nil {
		return nil, fmt.Errorf("%s: %w", annotationExempt, err)
	}
	deadline := annotations[annotationDelay]
	if deadline == "" {
		deadline = cfg.defaultDeadline
	}
	if n, err := strconv.Atoi(deadline); err != nil || n < 1 || n > 600000 {
		return nil, fmt.Errorf("%s must be an integer between 1 and 600000 milliseconds", annotationDelay)
	}

	initContainer := map[string]any{
		"name":            initName,
		"image":           cfg.proxyImage,
		"imagePullPolicy": cfg.proxyImagePull,
		"command":         []string{"/bin/sh", "-ec"},
		"args":            []string{routingScript},
		"env": []any{
			fieldEnv("RRPE_INBOUND_PORTS", "metadata.annotations['"+annotationIn+"']"),
			fieldEnv("RRPE_OUTBOUND_PORTS", "metadata.annotations['"+annotationOut+"']"),
			fieldEnv("RRPE_EXCLUDED_OUTBOUND_PORTS", "metadata.annotations['"+annotationExempt+"']"),
			map[string]any{"name": "RRPE_PROXY_PORT", "value": annotations[annotationProxy]},
		},
		"securityContext": map[string]any{
			"runAsUser": 0, "runAsGroup": 0, "runAsNonRoot": false,
			"allowPrivilegeEscalation": false,
			"capabilities": map[string]any{"drop": []string{"ALL"}, "add": []string{"NET_ADMIN"}},
		},
	}
	proxyEnv := []any{
		map[string]any{"name": "PROXY_ROLE", "value": role},
		map[string]any{"name": "PROXY_LISTEN_HOST", "value": "0.0.0.0"},
		map[string]any{"name": "PROXY_LISTEN_PORT", "value": strconv.Itoa(proxyPort)},
		map[string]any{"name": "REQUEST_CONTEXT_TTL_MILLIS", "value": cfg.contextTTL},
		fieldEnv("SERVICE_NAME", "metadata.labels['app']"),
		map[string]any{
			"name": "OTEL_EXPORTER_OTLP_ENDPOINT",
			"valueFrom": map[string]any{
				"configMapKeyRef": map[string]any{
					"name": "rpe-config",
					"key":  "OTEL_EXPORTER_OTLP_ENDPOINT",
				},
			},
		},
	}
	if role == "gateway" {
		proxyEnv = append(proxyEnv, map[string]any{"name": "DEFAULT_DEADLINE_MILLIS", "value": deadline})
		proxyEnv = append(proxyEnv, map[string]any{
			"name": "GATEWAY_DEADLINE_RULES",
			"valueFrom": map[string]any{
				"configMapKeyRef": map[string]any{
					"name": "rpe-config",
					"key":  "GATEWAY_DEADLINE_RULES",
				},
			},
		})
	} else {
		proxyEnv = append(proxyEnv, map[string]any{"name": "PROXY_DETECT_INBOUND_DEADLINE", "value": "true"})
	}
	proxyContainer := map[string]any{
		"name":            proxyName,
		"image":           cfg.proxyImage,
		"imagePullPolicy": cfg.proxyImagePull,
		"env":             proxyEnv,
		"ports": []any{
			map[string]any{"name": "proxy", "containerPort": proxyPort, "protocol": "TCP"},
			map[string]any{"name": "metrics", "containerPort": metricsPort, "protocol": "TCP"},
		},
		"readinessProbe": map[string]any{"tcpSocket": map[string]any{"port": "proxy"}, "periodSeconds": 5},
		"livenessProbe":  map[string]any{"tcpSocket": map[string]any{"port": "proxy"}, "initialDelaySeconds": 10, "periodSeconds": 10},
		"securityContext": map[string]any{
			"runAsUser": 0, "runAsGroup": 0, "runAsNonRoot": false,
			"allowPrivilegeEscalation": false,
			"capabilities": map[string]any{"drop": []string{"ALL"}, "add": []string{"NET_RAW"}},
		},
	}

	patch := make([]patchOperation, 0, 4)
	if annotations == nil {
		newAnnotations := map[string]string{annotationDone: "true"}
		for k, v := range annotations {
			newAnnotations[k] = v
		}
		patch = append(patch, patchOperation{Op: "add", Path: "/metadata/annotations", Value: newAnnotations})
	} else {
		patch = append(patch, patchOperation{Op: "add", Path: "/metadata/annotations/" + escapeJSONPointer(annotationDone), Value: "true"})
	}
	if p.Spec.InitContainers == nil {
		patch = append(patch, patchOperation{Op: "add", Path: "/spec/initContainers", Value: []any{initContainer}})
	} else {
		patch = append(patch, patchOperation{Op: "add", Path: "/spec/initContainers/-", Value: initContainer})
	}
	if p.Spec.Containers == nil {
		patch = append(patch, patchOperation{Op: "add", Path: "/spec/containers", Value: []any{proxyContainer}})
	} else {
		patch = append(patch, patchOperation{Op: "add", Path: "/spec/containers/-", Value: proxyContainer})
	}
	return patch, nil
}

func fieldEnv(name, fieldPath string) any {
	return map[string]any{
		"name": name,
		"valueFrom": map[string]any{
			"fieldRef": map[string]any{"fieldPath": fieldPath, "apiVersion": "v1"},
		},
	}
}

func parseSinglePort(value string) (int, error) {
	ports, err := parsePorts(value, true)
	if err != nil {
		return 0, err
	}
	if len(ports) != 1 || ports[0].first != ports[0].last {
		return 0, fmt.Errorf("must be one TCP port")
	}
	return ports[0].first, nil
}

func parsePorts(value string, required bool) ([]portRange, error) {
	if value == "" {
		if required {
			return nil, fmt.Errorf("is required")
		}
		return nil, nil
	}
	parts := strings.Split(value, ",")
	if len(parts) > 15 {
		return nil, fmt.Errorf("has more than 15 entries, exceeding iptables multiport limits")
	}
	result := make([]portRange, 0, len(parts))
	for _, part := range parts {
		rangeParts := strings.Split(part, ":")
		if len(rangeParts) > 2 || len(rangeParts) == 0 {
			return nil, fmt.Errorf("%q is not a port or port range", part)
		}
		first, err := parsePortNumber(rangeParts[0])
		if err != nil {
			return nil, err
		}
		last := first
		if len(rangeParts) == 2 {
			last, err = parsePortNumber(rangeParts[1])
			if err != nil {
				return nil, err
			}
			if first > last {
				return nil, fmt.Errorf("port range %q has its start after its end", part)
			}
		}
		result = append(result, portRange{first: first, last: last})
	}
	return result, nil
}

func parsePortNumber(value string) (int, error) {
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return 0, fmt.Errorf("port %q must be an integer between 1 and 65535", value)
	}
	return port, nil
}

func escapeJSONPointer(s string) string {
	s = strings.ReplaceAll(s, "~", "~0")
	return strings.ReplaceAll(s, "/", "~1")
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

const routingScript = `# Values are populated through the Downward API from Pod annotations.
validate_ports() {
  value="$1"
  [ -z "$value" ] && return 0
  case "$value" in *[!0-9,:-]*) echo "invalid port list: $value" >&2; exit 1;; esac
}
validate_ports "$RRPE_INBOUND_PORTS"
validate_ports "$RRPE_OUTBOUND_PORTS"
validate_ports "$RRPE_EXCLUDED_OUTBOUND_PORTS"
case "$RRPE_PROXY_PORT" in ''|*[!0-9]*) echo "invalid proxy port" >&2; exit 1;; esac
iptables -t mangle -N SIDECAR_TPROXY 2>/dev/null || true
iptables -t mangle -F SIDECAR_TPROXY
iptables -t mangle -A SIDECAR_TPROXY -i lo -j RETURN
iptables -t mangle -A SIDECAR_TPROXY -p tcp -m multiport --dports "$RRPE_INBOUND_PORTS" -j TPROXY --on-port "$RRPE_PROXY_PORT" --tproxy-mark 0x1/0x1
iptables -t mangle -C PREROUTING -j SIDECAR_TPROXY 2>/dev/null || iptables -t mangle -I PREROUTING 1 -j SIDECAR_TPROXY
ip rule add fwmark 0x1/0x1 lookup 100 2>/dev/null || true
ip route replace local 0.0.0.0/0 dev lo table 100
iptables -t nat -N SIDECAR_EGRESS 2>/dev/null || true
iptables -t nat -F SIDECAR_EGRESS
iptables -t nat -A SIDECAR_EGRESS -m owner --uid-owner 0 -j RETURN
if [ -n "$RRPE_EXCLUDED_OUTBOUND_PORTS" ]; then
  iptables -t nat -A SIDECAR_EGRESS -p tcp -m multiport --dports "$RRPE_EXCLUDED_OUTBOUND_PORTS" -j RETURN
fi
if [ -n "$RRPE_OUTBOUND_PORTS" ]; then
  iptables -t nat -A SIDECAR_EGRESS -p tcp -m multiport --dports "$RRPE_OUTBOUND_PORTS" -j REDIRECT --to-ports "$RRPE_PROXY_PORT"
fi
iptables -t nat -C OUTPUT -j SIDECAR_EGRESS 2>/dev/null || iptables -t nat -I OUTPUT 1 -j SIDECAR_EGRESS
`
