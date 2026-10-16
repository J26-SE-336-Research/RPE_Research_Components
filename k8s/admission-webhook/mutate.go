package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// Annotations and labels understood by the webhook.
const (
	annotationPrefix  = "resilience.rpe.dev/"
	annotationInject  = annotationPrefix + "inject" // "disabled" opts a pod out inside an enabled namespace
	annotationRole    = annotationPrefix + "role"   // gateway | service (default service)
	annotationIn      = annotationPrefix + "inbound-ports"
	annotationOut     = annotationPrefix + "outbound-ports"
	annotationExempt  = annotationPrefix + "excluded-outbound-ports"
	annotationMode    = annotationPrefix + "inbound-mode" // redirect | tproxy
	annotationService = annotationPrefix + "service"      // RPE service name (default: label app)
	annotationBudget  = annotationPrefix + "default-deadline-ms"
	annotationDone    = annotationPrefix + "injected"

	sidecarName = "rpe-sidecar"
	initName    = "rpe-init"

	portOutbound = 15001
	portInbound  = 15006
	portAdmin    = 15020
	portMetrics  = 15090
	proxyID      = 1337 // sidecar UID/GID; egress interception skips this UID and GID
)

var reservedPorts = []int{portOutbound, portInbound, portAdmin, portMetrics}

type config struct {
	sidecarImage            string
	sidecarPullPolicy       string
	defaultOutboundPorts    string
	defaultInboundMode      string
	defaultDeadlineMs       string
	policyAPIURL            string
	hubURL                  string
	otelEndpoint            string
	certFile, keyFile, port string
}

type pod struct {
	Metadata struct {
		Name         string            `json:"name"`
		GenerateName string            `json:"generateName"`
		Namespace    string            `json:"namespace"`
		Labels       map[string]string `json:"labels"`
		Annotations  map[string]string `json:"annotations"`
	} `json:"metadata"`
	Spec struct {
		Containers     []container `json:"containers"`
		InitContainers []container `json:"initContainers"`
	} `json:"spec"`
}

type container struct {
	Name  string `json:"name"`
	Ports []struct {
		ContainerPort int    `json:"containerPort"`
		Protocol      string `json:"protocol"`
	} `json:"ports"`
}

type patchOperation struct {
	Op    string `json:"op"`
	Path  string `json:"path"`
	Value any    `json:"value"`
}

type portRange struct{ first, last int }

// mutatePod returns the JSON patch that injects the RPE sidecar. The MutatingWebhookConfiguration
// only sends Pods from namespaces labelled rpe-injection=enabled, so every Pod received is eligible
// unless it opts out or was already injected.
func mutatePod(raw json.RawMessage, cfg config) ([]patchOperation, error) {
	var p pod
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("invalid Pod object: %w", err)
	}
	ann := p.Metadata.Annotations
	if ann[annotationInject] == "disabled" || ann[annotationDone] == "true" {
		return nil, nil
	}
	for _, c := range p.Spec.Containers {
		if c.Name == sidecarName {
			return nil, fmt.Errorf("pod already has a %q container", sidecarName)
		}
	}
	for _, c := range p.Spec.InitContainers {
		if c.Name == initName {
			return nil, fmt.Errorf("pod already has a %q init container", initName)
		}
	}

	role := orDefault(ann[annotationRole], "service")
	if role != "gateway" && role != "service" {
		return nil, fmt.Errorf("%s must be gateway or service", annotationRole)
	}
	mode := orDefault(ann[annotationMode], cfg.defaultInboundMode)
	if mode != "redirect" && mode != "tproxy" {
		return nil, fmt.Errorf("%s must be redirect or tproxy", annotationMode)
	}
	inboundSpec := ann[annotationIn]
	if inboundSpec == "" {
		inboundSpec = declaredPorts(p)
	}
	inbound, err := parsePorts(inboundSpec, true)
	if err != nil {
		return nil, fmt.Errorf("%s: %w (declare containerPorts or set the annotation)", annotationIn, err)
	}
	for _, pr := range inbound {
		for _, reserved := range reservedPorts {
			if reserved >= pr.first && reserved <= pr.last {
				return nil, fmt.Errorf("%s overlaps reserved sidecar port %d", annotationIn, reserved)
			}
		}
	}
	outboundSpec := orDefault(ann[annotationOut], cfg.defaultOutboundPorts)
	if _, err := parsePorts(outboundSpec, false); err != nil {
		return nil, fmt.Errorf("%s: %w", annotationOut, err)
	}
	if _, err := parsePorts(ann[annotationExempt], false); err != nil {
		return nil, fmt.Errorf("%s: %w", annotationExempt, err)
	}
	budget := orDefault(ann[annotationBudget], cfg.defaultDeadlineMs)
	if n, err := strconv.Atoi(budget); err != nil || n < 1 || n > 600000 {
		return nil, fmt.Errorf("%s must be an integer between 1 and 600000", annotationBudget)
	}
	serviceName := orDefault(ann[annotationService], p.Metadata.Labels["app"])
	if serviceName == "" {
		serviceName = orDefault(p.Metadata.Labels["app.kubernetes.io/name"], strings.TrimSuffix(p.Metadata.GenerateName, "-"))
	}

	initC := map[string]any{
		"name":            initName,
		"image":           cfg.sidecarImage,
		"imagePullPolicy": cfg.sidecarPullPolicy,
		"command":         []string{"/bin/sh", "-ec", routingScript},
		"env": []any{
			envVar("RPE_INBOUND_MODE", mode),
			envVar("RPE_INBOUND_PORTS", inboundSpec),
			envVar("RPE_OUTBOUND_PORTS", outboundSpec),
			envVar("RPE_EXCLUDED_OUTBOUND_PORTS", ann[annotationExempt]),
		},
		"securityContext": map[string]any{
			"runAsUser": 0, "runAsNonRoot": false, "allowPrivilegeEscalation": false,
			"capabilities": map[string]any{"drop": []string{"ALL"}, "add": []string{"NET_ADMIN", "NET_RAW"}},
		},
	}
	// REDIRECT mode needs no capabilities, so the sidecar runs as UID 1337. TPROXY mode needs
	// CAP_NET_ADMIN for IP_TRANSPARENT, which Kubernetes only grants to root; it then runs as
	// root with GID 1337 and the egress rules skip that group.
	sidecarSec := map[string]any{
		"runAsUser": proxyID, "runAsGroup": proxyID, "runAsNonRoot": true, "allowPrivilegeEscalation": false,
		"capabilities": map[string]any{"drop": []string{"ALL"}},
	}
	if mode == "tproxy" {
		sidecarSec = map[string]any{
			"runAsUser": 0, "runAsGroup": proxyID, "runAsNonRoot": false, "allowPrivilegeEscalation": false,
			"capabilities": map[string]any{"drop": []string{"ALL"}, "add": []string{"NET_ADMIN", "NET_RAW"}},
		}
	}
	sidecar := map[string]any{
		"name":            sidecarName,
		"image":           cfg.sidecarImage,
		"imagePullPolicy": cfg.sidecarPullPolicy,
		"env": []any{
			envVar("RPE_ROLE", role),
			envVar("RPE_SERVICE_NAME", serviceName),
			envVar("RPE_INBOUND_MODE", mode),
			envVar("RPE_APP_PORT", strconv.Itoa(inbound[0].first)),
			envVar("RPE_DEFAULT_DEADLINE_MS", budget),
			envVar("RPE_POLICY_API_URL", cfg.policyAPIURL),
			envVar("RPE_HUB_URL", cfg.hubURL),
			envVar("OTEL_EXPORTER_OTLP_ENDPOINT", cfg.otelEndpoint),
			fieldEnv("RPE_POD_NAME", "metadata.name"),
			fieldEnv("RPE_POD_NAMESPACE", "metadata.namespace"),
			fieldEnv("RPE_POD_IP", "status.podIP"),
		},
		"ports": []any{
			map[string]any{"name": "rpe-outbound", "containerPort": portOutbound},
			map[string]any{"name": "rpe-inbound", "containerPort": portInbound},
			map[string]any{"name": "rpe-admin", "containerPort": portAdmin},
			map[string]any{"name": "rpe-metrics", "containerPort": portMetrics},
		},
		"readinessProbe":  map[string]any{"httpGet": map[string]any{"path": "/rpe/v1/health", "port": portAdmin}, "periodSeconds": 5},
		"livenessProbe":   map[string]any{"httpGet": map[string]any{"path": "/rpe/v1/health", "port": portAdmin}, "initialDelaySeconds": 15, "periodSeconds": 10},
		"resources":       map[string]any{"requests": map[string]any{"cpu": "50m", "memory": "96Mi"}, "limits": map[string]any{"memory": "384Mi"}},
		"securityContext": sidecarSec,
	}

	patch := make([]patchOperation, 0, 4)
	if ann == nil {
		patch = append(patch, patchOperation{"add", "/metadata/annotations", map[string]string{annotationDone: "true"}})
	} else {
		patch = append(patch, patchOperation{"add", "/metadata/annotations/" + escapeJSONPointer(annotationDone), "true"})
	}
	if len(p.Spec.InitContainers) == 0 {
		patch = append(patch, patchOperation{"add", "/spec/initContainers", []any{initC}})
	} else {
		patch = append(patch, patchOperation{"add", "/spec/initContainers/-", initC})
	}
	patch = append(patch, patchOperation{"add", "/spec/containers/-", sidecar})
	return patch, nil
}

// declaredPorts returns the TCP containerPorts of the application containers.
func declaredPorts(p pod) string {
	var ports []int
	seen := map[int]bool{}
	for _, c := range p.Spec.Containers {
		for _, port := range c.Ports {
			if (port.Protocol == "" || port.Protocol == "TCP") && !seen[port.ContainerPort] {
				seen[port.ContainerPort] = true
				ports = append(ports, port.ContainerPort)
			}
		}
	}
	sort.Ints(ports)
	parts := make([]string, len(ports))
	for i, port := range ports {
		parts[i] = strconv.Itoa(port)
	}
	return strings.Join(parts, ",")
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
		return nil, fmt.Errorf("has more than 15 entries (iptables multiport limit)")
	}
	result := make([]portRange, 0, len(parts))
	for _, part := range parts {
		first, last, isRange := strings.Cut(part, ":")
		a, err := parsePortNumber(first)
		if err != nil {
			return nil, err
		}
		b := a
		if isRange {
			if b, err = parsePortNumber(last); err != nil {
				return nil, err
			}
			if a > b {
				return nil, fmt.Errorf("port range %q has its start after its end", part)
			}
		}
		result = append(result, portRange{a, b})
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

func envVar(name, value string) any { return map[string]any{"name": name, "value": value} }

func fieldEnv(name, fieldPath string) any {
	return map[string]any{"name": name, "valueFrom": map[string]any{"fieldRef": map[string]any{"fieldPath": fieldPath}}}
}

func escapeJSONPointer(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, "~", "~0"), "/", "~1")
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

// routingScript runs in the rpe-init container. Inbound traffic to the application ports goes to
// the sidecar (REDIRECT or TPROXY to 15006); outbound traffic to the listed ports is redirected to
// 15001. Traffic from the sidecar itself (UID or GID 1337) and to localhost is never redirected,
// so root application containers ARE intercepted.
const routingScript = `
check() { case "$1" in *[!0-9,:]*) echo "invalid port list: $1" >&2; exit 1;; esac; }
check "$RPE_INBOUND_PORTS"; check "$RPE_OUTBOUND_PORTS"; check "$RPE_EXCLUDED_OUTBOUND_PORTS"
if [ "$RPE_INBOUND_MODE" = "tproxy" ]; then
  iptables -t mangle -N RPE_INBOUND
  iptables -t mangle -A RPE_INBOUND -i lo -j RETURN
  iptables -t mangle -A RPE_INBOUND -p tcp -m multiport --dports "$RPE_INBOUND_PORTS" -j TPROXY --on-port 15006 --tproxy-mark 0x1/0x1
  iptables -t mangle -I PREROUTING 1 -j RPE_INBOUND
  ip rule add fwmark 0x1/0x1 lookup 100
  ip route replace local 0.0.0.0/0 dev lo table 100
else
  iptables -t nat -N RPE_INBOUND
  iptables -t nat -A RPE_INBOUND -p tcp -m multiport --dports "$RPE_INBOUND_PORTS" -j REDIRECT --to-ports 15006
  iptables -t nat -I PREROUTING 1 -j RPE_INBOUND
fi
iptables -t nat -N RPE_OUTBOUND
iptables -t nat -A RPE_OUTBOUND -m owner --uid-owner 1337 -j RETURN
iptables -t nat -A RPE_OUTBOUND -m owner --gid-owner 1337 -j RETURN
iptables -t nat -A RPE_OUTBOUND -d 127.0.0.0/8 -j RETURN
if [ -n "$RPE_EXCLUDED_OUTBOUND_PORTS" ]; then
  iptables -t nat -A RPE_OUTBOUND -p tcp -m multiport --dports "$RPE_EXCLUDED_OUTBOUND_PORTS" -j RETURN
fi
if [ -n "$RPE_OUTBOUND_PORTS" ]; then
  iptables -t nat -A RPE_OUTBOUND -p tcp -m multiport --dports "$RPE_OUTBOUND_PORTS" -j REDIRECT --to-ports 15001
fi
iptables -t nat -I OUTPUT 1 -j RPE_OUTBOUND
echo "rpe-init: inbound=$RPE_INBOUND_PORTS mode=$RPE_INBOUND_MODE outbound=$RPE_OUTBOUND_PORTS"
`
