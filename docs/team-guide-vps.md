# Testing on the shared cluster: team guide

One shared Kubernetes cluster (a rented server) runs the whole RPE platform and the demo app, so you do not need Docker or kind on your laptop. **Only one version runs at a time: tell the team in the group chat before you deploy or change things.**

## 1. Get access (once)

Everyone logs in to the server as the same user, `deploy`. Kalhara gives you the **server IP and the password privately** (never from this repo or the group chat).

1. Add a shortcut to `~/.ssh/config` (Git Bash, or PowerShell on Windows; create the file if it does not exist):
   ```
   Host rpe-vps
     HostName <VPS_IP>
     User deploy
   ```
2. Test it: `ssh rpe-vps`. Accept the host fingerprint (`yes`) the first time and type the password when asked (nothing is shown while typing).
3. On the server, run `kubectl get nodes` (expect one node, `Ready`).

Termius works too: new host, the same IP, username `deploy`, the password.

Keep the password to yourself: it gives full control of the cluster. If you think it leaked, tell Kalhara so it can be changed.

## 2. Open the Admin Panel

```
ssh -L 8800:localhost:8800 rpe-vps 'KUBECONFIG=$HOME/.kube/config kubectl -n rpe-system port-forward svc/rpe-hub 8800:80'
```

Leave that window open, then browse to http://localhost:8800. Logins: `admin/admin`, `sre/sre`, `viewer/viewer` (viewer is read-only). If the tunnel drops, run the command again.

The Admin Panel and the Kubernetes API are **not** reachable from the internet, only through your SSH tunnel. Keep it that way.

## 3. See what is running

Run these after `ssh rpe-vps`:

```
kubectl get pods -A                                   # everything; demo pods show 2/2 (app + sidecar)
kubectl -n rpe-demo get pods
kubectl -n rpe-demo logs deploy/gateway -c rpe-sidecar --tail=50     # C2 sidecar logs
kubectl -n rpe-system logs deploy/rpe-operator --tail=50             # C3 operator
kubectl -n rpe-system logs deploy/rpe-intelligence --tail=50         # C4
kubectl -n rpe-system logs deploy/rpe-hub --tail=50
```

Send a request through the chain:

```
kubectl -n rpe-e2e exec e2e-client -- curl -si http://gateway.rpe-demo:8080/api/orders
```

Inject a fault and call again (C1/C2 deadline and cancellation behaviour):

```
kubectl -n rpe-e2e exec e2e-client -- curl -s -X POST http://payment-svc.rpe-demo:8080/admin/faults -d '{"latencyMs":1500}'
```

Clear it afterwards with `{"latencyMs":0}`.

## 4. Test your own change

1. Push your branch to GitHub.
2. In GitHub: **Actions > Deploy to VPS > Run workflow**, choose your branch, keep "run e2e" ticked.
   (Pushing to `Integrated` deploys automatically. If the Run workflow button is missing, the workflow file is not on the default branch yet: ask Kalhara.)
3. The pipeline builds all images, deploys them (about 5-15 minutes) and runs the smoke tests (suites 10, 60, 90).
4. Check your feature with the commands above or in the Admin Panel, then post the result in the group chat.

Each deployment replaces the previous one. Do not deploy while someone else is testing.

## 5. Rules

- Do not run `kubectl delete`, `apply` or `edit` on the cluster by hand: the next deployment overwrites it, and it confuses everyone else. Use the Admin Panel or the pipeline.
- Do not share the `deploy` password, `~/.kube/config` or any token in chats or the repo.
- Heavy load tests only when nobody else is testing (the server has 11 GiB RAM).
- Something broken? Post the output of `kubectl get pods -A` and the relevant `kubectl logs ...` in the group chat.
