package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// groundTruth is the real call graph we compare the rebuilt topology with. It can't come from the trails, or we
// would be comparing the trails with themselves. It is either set by hand in GROUND_TRUTH or read from the demo
// deployments, where each service lists the services it calls in CHILDREN.
type groundTruth struct {
	mu     sync.RWMutex
	edges  [][2]string
	source string
}

func (g *groundTruth) get() ([][2]string, string) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return append([][2]string(nil), g.edges...), g.source
}

func (g *groundTruth) set(edges [][2]string, source string) {
	g.mu.Lock()
	g.edges, g.source = edges, source
	g.mu.Unlock()
}

// deployment is the part of a Kubernetes Deployment we need for the call graph.
type deployment struct {
	Metadata struct {
		Name string `json:"name"`
	} `json:"metadata"`
	Spec struct {
		Template struct {
			Spec struct {
				Containers []struct {
					Name string `json:"name"`
					Env  []struct {
						Name  string `json:"name"`
						Value string `json:"value"`
					} `json:"env"`
				} `json:"containers"`
			} `json:"spec"`
		} `json:"template"`
	} `json:"spec"`
}

// edgesFromDeployments reads the calls each service declares: SERVICE_NAME, or the deployment name, calls the host
// of every CHILDREN url. The sidecar container is skipped.
func edgesFromDeployments(items []deployment) [][2]string {
	seen := map[[2]string]bool{}
	var out [][2]string
	for _, d := range items {
		for _, c := range d.Spec.Template.Spec.Containers {
			if c.Name == "rpe-sidecar" {
				continue
			}
			from, children := d.Metadata.Name, ""
			for _, e := range c.Env {
				switch e.Name {
				case "SERVICE_NAME":
					from = e.Value
				case "CHILDREN":
					children = e.Value
				}
			}
			for _, part := range strings.Split(children, ",") {
				_, raw, ok := strings.Cut(strings.TrimSpace(part), "=")
				if !ok {
					continue
				}
				u, err := url.Parse(strings.TrimSuffix(raw, "?optional"))
				if err != nil || u.Hostname() == "" {
					continue
				}
				to := u.Hostname()
				if i := strings.IndexByte(to, '.'); i > 0 {
					to = to[:i] // service.namespace.svc -> service
				}
				if e := [2]string{from, to}; !seen[e] {
					seen[e] = true
					out = append(out, e)
				}
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i][0]+">"+out[i][1] < out[j][0]+">"+out[j][1] })
	return out
}

// kubeReader is a very small in-cluster client that only lists the deployments of one namespace. The permissions
// for that are in deploy/k8s/platform/protocol.yaml.
type kubeReader struct {
	base, token string
	client      *http.Client
}

func newKubeReader() *kubeReader {
	const sa = "/var/run/secrets/kubernetes.io/serviceaccount"
	token, err := os.ReadFile(sa + "/token")
	if err != nil {
		return nil // not running in a cluster
	}
	pool := x509.NewCertPool()
	if ca, err := os.ReadFile(sa + "/ca.crt"); err == nil {
		pool.AppendCertsFromPEM(ca)
	}
	return &kubeReader{base: "https://" + os.Getenv("KUBERNETES_SERVICE_HOST") + ":" + os.Getenv("KUBERNETES_SERVICE_PORT"),
		token: strings.TrimSpace(string(token)), client: &http.Client{Timeout: 10 * time.Second,
			Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}}}}
}

func (k *kubeReader) deployments(ctx context.Context, namespace string) ([]deployment, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, k.base+"/apis/apps/v1/namespaces/"+url.PathEscape(namespace)+"/deployments", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+k.token)
	resp, err := k.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("kubernetes API: %s", resp.Status)
	}
	var list struct {
		Items []deployment `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&list); err != nil {
		return nil, err
	}
	return list.Items, nil
}

// followDeployments reloads the call graph from the deployments every interval. If a read fails we keep the last
// one.
func (g *groundTruth) followDeployments(ctx context.Context, k *kubeReader, namespace string, interval time.Duration) {
	for {
		if items, err := k.deployments(ctx, namespace); err != nil {
			log.Printf("ground truth: %v (keeping the last known call graph)", err)
		} else if edges := edgesFromDeployments(items); len(edges) > 0 {
			g.set(edges, "declared in the deployments of namespace "+namespace+" (CHILDREN)")
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}
