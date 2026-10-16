// Command rpe-operator is the C3 RPE Operator and policy API.
//
// It detects approved policies through PostgreSQL LISTEN/NOTIFY (with catch-up from policy_events after
// downtime), mirrors them as RPEPolicy resources, drives RPERollout resources through a two-phase
// (stage → activate at the gateway first → drain) or naive rollout to the running proxies, records every
// delivery, keeps a Redis fast path (events, effective policies, acks) and repairs drift.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"strconv"
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
		Metrics:                metricsserver.Options{BindAddress: "0"},
		HealthProbeBindAddress: "0",
	})
	must(err)

	st, err := newStore(ctx, dbURL)
	must(err)
	b := newBus(env("REDIS_ADDR", "rpe-redis.rpe-system:6379"))
	side := newSidecars()
	rec := &RolloutReconciler{Client: mgr.GetClient(), reader: mgr.GetAPIReader(), store: st, bus: b, side: side, demoNS: demoNS,
		drain: time.Duration(envInt("DRAIN_MS", 2000)) * time.Millisecond, attempts: envInt("MAX_ATTEMPTS", 30)}
	must(ctrl.NewControllerManagedBy(mgr).For(&RPERollout{}).Complete(rec))

	w := newWaiters()
	a := &api{k: mgr.GetClient(), store: st, bus: b, ns: ns, waiters: w}
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
		repairDrift(ctx, time.Duration(envInt("DRIFT_INTERVAL_MS", 10000))*time.Millisecond, mgr.GetAPIReader(), st, b, side, demoNS)
	}()

	log.Printf("rpe-operator: policy API on %s, rollouts in %s, proxies in %s", srv.Addr, ns, demoNS)
	must(mgr.Start(ctx))
}

// catchUp mirrors every approval after the Redis checkpoint into an RPEPolicy resource.
func catchUp(ctx context.Context, k client.Client, st *store, b *bus, ns string, autoRollout bool) {
	events, err := st.approvalsSince(ctx, b.checkpoint(ctx))
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
					Spec: RolloutSpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Mode: "two-phase",
						PreviousEpoch: st.activeEpoch(ctx, p.PolicyID), RequestedBy: "auto"}}
				_ = k.Create(ctx, ro)
			}
		}
		b.setCheckpoint(ctx, e.ID)
	}
}

func upsertPolicyCR(ctx context.Context, k client.Client, ns string, p Policy) {
	spec := PolicySpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Route: p.Scope.Route,
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
