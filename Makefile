# RPE (J26-SE-336) monorepo tasks. Every target wraps a script in scripts/ so it also works without make.
.PHONY: help prereqs test-unit cluster images deploy restart reset-data console test-e2e test all undeploy cluster-down clean

help:            ## list targets
	@grep -E '^[a-z0-9-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

prereqs:         ## check the local toolchain
	@scripts/prereqs.sh

test-unit:       ## unit tests of every component (SKIP_JAVA=1 to skip Maven)
	@scripts/test-unit.sh

cluster:         ## create the local kind cluster
	@scripts/cluster-up.sh

images:          ## build all images and load them into kind (IMAGES="sidecar demo-app" for a subset)
	@scripts/build-images.sh $(IMAGES)

deploy:          ## deploy platform + demo topology into kind
	@scripts/deploy.sh

restart:         ## restart RPE workloads to pick up rebuilt :dev images
	@scripts/restart.sh

reset-data:      ## clean slate for demos: empty policy store, Redis, RPE resources; restart demo services
	@scripts/reset-data.sh

console:         ## open the Admin Panel at http://localhost:8800 (port-forward; Ctrl-C to stop)
	@scripts/console.sh

test-e2e:        ## run Kubernetes e2e suites (SUITES="30 35" for a subset)
	@scripts/test-e2e.sh $(SUITES)

test: test-unit test-e2e ## unit + e2e

all: test-unit cluster images deploy test-e2e ## full pipeline from scratch

undeploy:        ## remove everything RPE from the cluster
	@scripts/undeploy.sh

cluster-down:    ## delete the kind cluster
	@scripts/cluster-down.sh

clean:           ## remove build outputs
	rm -rf sidecar/target intelligence/target deploy/k8s/generated tests/e2e/reports
