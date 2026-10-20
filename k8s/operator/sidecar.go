package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// sidecars talks to each proxy's control API on :15020 (contract A3.5).
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

// sidecarEpochs is GET /rpe/v1/policy: the epochs a proxy holds (with the content hash it computed), the
// in-flight requests pinned to each epoch and the mixed-policy requests it served (A3.5 v0.3).
type sidecarEpochs struct {
	Epochs []struct {
		Epoch   string `json:"epoch"`
		Version int    `json:"version"`
		Hash    string `json:"hash"`
		State   string `json:"state"`
	} `json:"epochs"`
	ActiveEpoch string           `json:"activeEpoch"`
	Inflight    map[string]int64 `json:"inflight"`
	Mismatched  int64            `json:"mismatched"`
}

func (s *sidecars) do(ctx context.Context, method, ip, path string, body any, out any) error {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://"+s.addr(ip)+path, rd)
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

// addr is ip:port; an IPv4 address that already carries a port (tests) is used as is.
func (s *sidecars) addr(ip string) string {
	switch strings.Count(ip, ":") {
	case 0:
		return ip + ":" + strconv.Itoa(s.port)
	case 1:
		return ip
	}
	return "[" + ip + "]:" + strconv.Itoa(s.port)
}

// put stages or activates a policy and returns what the proxy holds afterwards.
func (s *sidecars) put(ctx context.Context, ip string, p stagedPolicy) (sidecarEpochs, error) {
	var out sidecarEpochs
	err := s.do(ctx, http.MethodPut, ip, "/rpe/v1/policy", p, &out)
	return out, err
}

func (s *sidecars) activate(ctx context.Context, ip, epoch string) (sidecarEpochs, error) {
	var out sidecarEpochs
	err := s.do(ctx, http.MethodPost, ip, "/rpe/v1/policy/activate", map[string]string{"epoch": epoch}, &out)
	return out, err
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

// hashOf is the content hash the proxy computed for epoch ("" when it does not hold it).
func (e sidecarEpochs) hashOf(epoch string) string {
	for _, x := range e.Epochs {
		if x.Epoch == epoch {
			return x.Hash
		}
	}
	return ""
}

// verify checks that the proxy holds epoch with exactly the approved content.
func (e sidecarEpochs) verify(epoch, hash string) error {
	got := e.hashOf(epoch)
	switch {
	case got == "":
		return fmt.Errorf("proxy does not hold %s", epoch)
	case got != hash:
		return fmt.Errorf("hash mismatch: proxy holds %s as %s, approved %s", epoch, got, hash)
	}
	return nil
}
