package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// sidecars talks to each proxy's control API on :15020.
type sidecars struct {
	http *http.Client
	port int
}

func newSidecars() *sidecars {
	return &sidecars{http: &http.Client{Timeout: 5 * time.Second}, port: 15020}
}

type stagedPolicy struct {
	Epoch   string          `json:"epoch"` // first field: the sidecar reads top-level fields
	Version int             `json:"version"`
	Hash    string          `json:"hash"`
	State   string          `json:"state"`
	Policy  json.RawMessage `json:"policy"`
}

type sidecarEpochs struct {
	Epochs []struct {
		Epoch string `json:"epoch"`
		State string `json:"state"`
	} `json:"epochs"`
	ActiveEpoch string `json:"activeEpoch"`
}

func (s *sidecars) do(ctx context.Context, method, ip, path string, body any, out any) error {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, fmt.Sprintf("http://%s:%d%s", ip, s.port, path), rd)
	if err != nil {
		return err
	}
	resp, err := s.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s %s: HTTP %d %s", method, path, resp.StatusCode, bytes.TrimSpace(data))
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

func (s *sidecars) put(ctx context.Context, ip string, p stagedPolicy) error {
	return s.do(ctx, http.MethodPut, ip, "/rpe/v1/policy", p, nil)
}

func (s *sidecars) activate(ctx context.Context, ip, epoch string) error {
	return s.do(ctx, http.MethodPost, ip, "/rpe/v1/policy/activate", map[string]string{"epoch": epoch}, nil)
}

func (s *sidecars) retire(ctx context.Context, ip, epoch string) error {
	return s.do(ctx, http.MethodDelete, ip, "/rpe/v1/policy/"+epoch, nil, nil)
}

func (s *sidecars) epochs(ctx context.Context, ip string) (sidecarEpochs, error) {
	var out sidecarEpochs
	err := s.do(ctx, http.MethodGet, ip, "/rpe/v1/policy", nil, &out)
	return out, err
}

// holds reports whether the proxy holds epoch in the given state.
func (e sidecarEpochs) holds(epoch, state string) bool {
	for _, x := range e.Epochs {
		if x.Epoch == epoch && x.State == state {
			return true
		}
	}
	return false
}
