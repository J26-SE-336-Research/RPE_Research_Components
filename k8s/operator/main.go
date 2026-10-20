// Command rpe-operator is the C3 RPE Operator and policy API.
//
// It detects approved policies through PostgreSQL LISTEN/NOTIFY (with catch-up from policy_events after
// downtime), mirrors them as RPEPolicy resources, drives RPERollout resources through a per-request
// consistent two-phase rollout (stage → activate at the gateway first → drain the previous epoch's in-flight
// requests) or a naive rollout, verifies every delivery by content hash, counts mixed-policy requests, keeps a
// Redis fast path (events, effective policies, acks), audits injection coverage and repairs drift.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/client"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
)

func main() {
	ctx := ctrl.SetupSignalHandler()
	dbURL := env("DATABASE_URL", "postgres://rpe:rpe-dev-password@rpe-postgres.rpe-system:5432/rpe?sslmode=disable")
	ns := currentNamespace()
	demoNS := env("DEMO_NAMESPACE", "rpe-demo")

	scheme := runtime.NewScheme()
	must(clientgoscheme.AddToScheme(scheme))
	must(addKnownTypes(scheme))
	mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
		Scheme:                 scheme,
		Cache:                  cache.Options{DefaultNamespaces: map[string]cache.Config{ns: {}}},
		Metrics:                metricsserver.Options{BindAddress: env("METRICS_ADDR", ":8081")},
		HealthProbeBindAddress: "0",
	})
	must(err)

	st, err := newStore(ctx, dbURL)
	must(err)
	b := newBus(env("REDIS_ADDR", "rpe-redis.rpe-system:6379"))
	side := newSidecars()
	rec := &RolloutReconciler{Client: mgr.GetClient(), reader: mgr.GetAPIReader(), store: st, bus: b, side: side, demoNS: demoNS,
		drain:    time.Duration(envInt("DRAIN_MAX_MS", envInt("DRAIN_MS", 30000))) * time.Millisecond,
		settle:   time.Duration(envInt("SETTLE_MS", 3000)) * time.Millisecond,
		attempts: envInt("MAX_ATTEMPTS", 30)}
	must(ctrl.NewControllerManagedBy(mgr).For(&RPERollout{}).Complete(rec))

	w := newWaiters()
	fl := &fleet{reader: mgr.GetAPIReader(), writer: mgr.GetClient(), store: st, side: side, bus: b, ns: ns}
	a := &api{k: mgr.GetClient(), store: st, bus: b, ns: ns, waiters: w, fleet: fl}
	srv := &http.Server{Addr: ":" + env("PORT", "8080"), Handler: a.routes(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()
	go func() { <-ctx.Done(); _ = srv.Shutdown(context.Background()) }()

	go func() {
		if !mgr.GetCache().WaitForCacheSync(ctx) {
			return
		}
		autoRollout := env("AUTO_ROLLOUT", "false") == "true"
		mirror := func() { catchUp(ctx, mgr.GetClient(), st, b, ns, autoRollout) }
		go st.listen(ctx, dbURL, func(payload string) {
			w.notify(payload)
			mirror()
		}, mirror)
		// Redis is a fast path that can be flushed at any time: rebuild what it lost from PostgreSQL
		go rehydrate(ctx, time.Duration(envInt("DRIFT_INTERVAL_MS", 10000))*time.Millisecond, st, b)
		// coverage audit + drift detection and repair over the fleet
		fl.run(ctx, time.Duration(envInt("DRIFT_INTERVAL_MS", 10000))*time.Millisecond, env("DRIFT_REPAIR", "true") == "true")
	}()

	log.Printf("rpe-operator: policy API on %s, rollouts in %s, proxies in %s", srv.Addr, ns, demoNS)
	must(mgr.Start(ctx))
}

// catchUpPosition is the last policy_events id the Operator handled. It is kept in PostgreSQL (policy.operator_state),
// not in Redis; the first time after an upgrade the position the old Operator kept in Redis is carried over.
func catchUpPosition(ctx context.Context, st *store, b *bus) int64 {
	if id, found := st.checkpoint(ctx); found {
		return id
	}
	legacy := b.legacyCheckpoint(ctx)
	if legacy > 0 {
		_ = st.setCheckpoint(ctx, legacy)
	}
	return legacy
}

// catchUp mirrors every approval after the stored checkpoint into an RPEPolicy resource.
func catchUp(ctx context.Context, k client.Client, st *store, b *bus, ns string, autoRollout bool) {
	events, err := st.approvalsSince(ctx, catchUpPosition(ctx, st, b))
	if err != nil {
		log.Printf("catch-up: %v", err)
		return
	}
	for _, e := range events {
		p, err := st.get(ctx, e.PolicyID, e.Version)
		if err == nil {
			upsertPolicyCR(ctx, k, ns, p)
			b.emit(ctx, "policy-approved", map[string]string{"policyId": p.PolicyID, "version": strconv.Itoa(p.Version), "epoch": p.Epoch, "approvedBy": p.ApprovedBy})
			if autoRollout {
				ro := &RPERollout{ObjectMeta: metav1.ObjectMeta{Name: rolloutName(p), Namespace: ns},
					Spec: rolloutSpec(p, "two-phase", st.activeEpoch(ctx, p.PolicyID), "auto")}
				_ = k.Create(ctx, ro)
			}
		}
		if err := st.setCheckpoint(ctx, e.ID); err != nil {
			log.Printf("catch-up: cannot store the checkpoint %d: %v", e.ID, err)
			return
		}
	}
}

func upsertPolicyCR(ctx context.Context, k client.Client, ns string, p Policy) {
	spec := PolicySpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: strings.Join(p.services(), ","), Route: p.Scope.Route,
		Settings: p.Spec, ApprovedBy: p.ApprovedBy, ApprovedAt: p.ApprovedAt.UTC().Format(time.RFC3339)}
	if !json.Valid(spec.Settings) || string(spec.Settings) == "null" {
		spec.Settings = json.RawMessage(`{}`)
	}
	var cr RPEPolicy
	err := k.Get(ctx, client.ObjectKey{Namespace: ns, Name: crName(p.PolicyID)}, &cr)
	if apierrors.IsNotFound(err) {
		cr = RPEPolicy{ObjectMeta: metav1.ObjectMeta{Name: crName(p.PolicyID), Namespace: ns}, Spec: spec}
		if err := k.Create(ctx, &cr); err != nil {
			log.Printf("create RPEPolicy %s: %v", cr.Name, err)
		}
		return
	}
	if err == nil && cr.Spec.Version < p.Version {
		cr.Spec = spec
		if err := k.Update(ctx, &cr); err != nil {
			log.Printf("update RPEPolicy %s: %v", cr.Name, err)
		}
	}
}

func currentNamespace() string {
	if b, err := os.ReadFile("/var/run/secrets/kubernetes.io/serviceaccount/namespace"); err == nil {
		return string(b)
	}
	return env("POD_NAMESPACE", "rpe-system")
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(k)); err == nil {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
