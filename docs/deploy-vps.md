# Shared test cluster (Contabo VPS, k3s)

A single-node k3s cluster that runs the whole RPE platform, so nobody needs to run kind locally.
It is **one shared environment**: every deployment replaces the previous one, so coordinate before testing.

## How it deploys

`.github/workflows/deploy-vps.yml` runs on every push to `Integrated` (or manually: Actions > Deploy to VPS > Run workflow, choosing any branch):

1. builds the seven images (including the C1 protocol service) and pushes them to `ghcr.io/<org>/rpe-<name>:<git-sha12>`;
2. opens an SSH tunnel to the k3s API (port 6443 is closed on the firewall);
3. runs `scripts/deploy-remote.sh` (registry-image variant of `scripts/deploy.sh`);
4. runs the e2e smoke suites 10, 60 and 90.

## Repository secrets (admin only)

| Secret | Value |
|---|---|
| `VPS_HOST` | server IP |
| `VPS_USER` | `deploy` |
| `VPS_SSH_KEY` | private key whose public half is in `/home/deploy/.ssh/authorized_keys` |
| `GHCR_PULL_USER` | GitHub username that owns the token |
| `GHCR_PULL_TOKEN` | classic PAT with `read:packages` (the cluster uses it to pull images) |

## Using the cluster

Ask the admin to add your SSH public key to the `deploy` user, then:

```bash
# Admin Panel on http://localhost:8800 (logins admin/admin, sre/sre, viewer/viewer)
ssh -L 8800:localhost:8800 deploy@<VPS_IP> 'KUBECONFIG=$HOME/.kube/config kubectl -n rpe-system port-forward svc/rpe-hub 8800:80'

# kubectl from your laptop: tunnel the API, then use a copy of the kubeconfig
ssh -N -L 6443:127.0.0.1:6443 deploy@<VPS_IP>
ssh deploy@<VPS_IP> 'cat ~/.kube/config' > ~/.kube/rpe-vps && export KUBECONFIG=~/.kube/rpe-vps
kubectl -n rpe-demo get pods
```

Never expose the Admin Panel or the Kubernetes API publicly: the demo logins and database password are dev defaults.

## Known limitations

- The Admin Panel's "create workload" feature (`hub/features.go`) hard-codes `rpe/demo-app:dev`; it will not find that image on the VPS.
- e2e suites 00 (expects two nodes) and 20 (builds pods from local `:dev` images) are not run in CI.
- 11 GiB RAM fits one environment; do not run heavy experiments while the team is testing.
