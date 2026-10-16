package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// kube is a minimal in-cluster Kubernetes API client (service-account token + CA), enough for the
// read-only fleet view and server-side dry-run admission. RBAC is in deploy/k8s/platform/hub.yaml.
type kube struct {
	base   string
	token  string
	client *http.Client
}

const saDir = "/var/run/secrets/kubernetes.io/serviceaccount"

func newKube() *kube {
	token, err := os.ReadFile(saDir + "/token")
	if err != nil {
		return nil // not in a cluster (local development)
	}
	pool := x509.NewCertPool()
	if ca, err := os.ReadFile(saDir + "/ca.crt"); err == nil {
		pool.AppendCertsFromPEM(ca)
	}
	host, port := os.Getenv("KUBERNETES_SERVICE_HOST"), os.Getenv("KUBERNETES_SERVICE_PORT")
	return &kube{
		base:   "https://" + host + ":" + port,
		token:  string(token),
		client: &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}}},
	}
}

func (k *kube) do(ctx context.Context, method, path string, body any, out any) (int, error) {
	if k == nil {
		return 0, fmt.Errorf("kubernetes API not available (hub is not running in a cluster)")
	}
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, k.base+path, rd)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Bearer "+k.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := k.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if out != nil {
		_ = json.Unmarshal(data, out)
	}
	return resp.StatusCode, nil
}

// Pod is the subset of a Kubernetes Pod used by the panel.
type Pod struct {
	Metadata struct {
		Name        string            `json:"name"`
		Namespace   string            `json:"namespace"`
		Labels      map[string]string `json:"labels"`
		Annotations map[string]string `json:"annotations"`
	} `json:"metadata"`
	Spec struct {
		Containers     []kContainer `json:"containers"`
		InitContainers []kContainer `json:"initContainers"`
	} `json:"spec"`
	Status struct {
		Phase                 string            `json:"phase"`
		PodIP                 string            `json:"podIP"`
		ContainerStatuses     []containerStatus `json:"containerStatuses"`
		InitContainerStatuses []containerStatus `json:"initContainerStatuses"`
	} `json:"status"`
}

type kContainer struct {
	Name            string `json:"name"`
	Image           string `json:"image"`
	SecurityContext *struct {
		RunAsUser  *int64 `json:"runAsUser"`
		RunAsGroup *int64 `json:"runAsGroup"`
	} `json:"securityContext"`
	Env []struct {
		Name  string `json:"name"`
		Value string `json:"value"`
	} `json:"env"`
}

type containerStatus struct {
	Name         string `json:"name"`
	Ready        bool   `json:"ready"`
	RestartCount int    `json:"restartCount"`
	State        struct {
		Terminated *struct {
			ExitCode int    `json:"exitCode"`
			Reason   string `json:"reason"`
		} `json:"terminated"`
	} `json:"state"`
}

func (k *kube) pods(ctx context.Context, ns string) ([]Pod, error) {
	var list struct{ Items []Pod }
	code, err := k.do(ctx, http.MethodGet, "/api/v1/namespaces/"+ns+"/pods", nil, &list)
	if err != nil {
		return nil, err
	}
	if code != http.StatusOK {
		return nil, fmt.Errorf("kubernetes API returned %d", code)
	}
	return list.Items, nil
}

type Namespace struct {
	Metadata struct {
		Name   string            `json:"name"`
		Labels map[string]string `json:"labels"`
	} `json:"metadata"`
}

func (k *kube) namespace(ctx context.Context, name string) (Namespace, error) {
	var ns Namespace
	code, err := k.do(ctx, http.MethodGet, "/api/v1/namespaces/"+name, nil, &ns)
	if err == nil && code != http.StatusOK {
		err = fmt.Errorf("kubernetes API returned %d", code)
	}
	return ns, err
}

// dryRunPod submits a Pod with dryRun=All: admission webhooks run (the RPE webhook declares
// sideEffects: None), nothing is persisted, and the mutated Pod or the rejection is returned.
func (k *kube) dryRunPod(ctx context.Context, ns string, pod map[string]any) (Pod, int, string, error) {
	var raw json.RawMessage
	code, err := k.do(ctx, http.MethodPost, "/api/v1/namespaces/"+ns+"/pods?dryRun=All", pod, &raw)
	if err != nil {
		return Pod{}, 0, "", err
	}
	if code >= 300 {
		var status struct{ Message string }
		_ = json.Unmarshal(raw, &status)
		return Pod{}, code, status.Message, nil
	}
	var p Pod
	_ = json.Unmarshal(raw, &p)
	return p, code, "", nil
}
