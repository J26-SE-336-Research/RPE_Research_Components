<#
Live viva demo of C4 on the kind cluster: real Prometheus and Tempo data, real C3 policies, real sidecars.
Uses only what the project already has: the in-cluster e2e-client pod, the hub (admin), C3's policy controls,
the demo-app fault switch (/admin/faults) and make reset-data. No code is changed.

  powershell -ExecutionPolicy Bypass -File intelligence\scripts\viva-live.ps1 warmup
  ... lowrate [-Service payment-svc] [-Rate 1]   leaky bucket of the service to Rate req/s: RATE_TOO_LOW
  ... overload [-Service payment-svc]            fault: latency 500 ms, 10% errors: APP_OVERLOADED
  ... recover [-Service payment-svc] [-Rate 100] fault off, rate back
  ... deadline [-DeadlineMs 100]                 GET /api/orders deadline: a deadline card
  ... status                                     health, latest c4-auto changes, held changes, waiting cards
  ... toohigh [-Service payment-svc] [-Rate 200] [-BurnMs 20] [-Parallel 8]
                                                 rate too high from the start: fault cpuBurnMs, a load pod busy/quiet
  ... toohigh-stop [-Service payment-svc] [-Rate 100]   undo toohigh: load pod deleted, fault off, rate back
  ... reset                                      everything back (make reset-data, needs make in Git Bash)
#>
param([Parameter(Position = 0)][string]$Step = "", [string]$Service = "payment-svc", [int]$Rate = 0, [int]$DeadlineMs = 100, [string]$Context = "kind-rpe",
    [int]$BurnMs = 20, [int]$Parallel = 8)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$C4 = "http://rpe-intelligence.rpe-system:8090/api/v2/intelligence"
$Hub = "http://rpe-hub.rpe-system"

function Pod([string[]]$CmdArgs) { & kubectl --context $Context -n rpe-e2e exec e2e-client -- @CmdArgs }
function C4([string]$Path) { (Pod @("curl", "-s", "--max-time", "30", "$C4$Path")) -join "`n" | ConvertFrom-Json }
function HubLogin { Pod @("curl", "-s", "--max-time", "30", "-c", "/tmp/jar-viva", "-o", "/dev/null", "-X", "POST", "-H", "Content-Type: application/json",
    "--data", '{"username":"admin","password":"admin"}', "$Hub/api/hub/login") | Out-Null }
function HubPost([string]$Path, [string]$Body) {
    $out = Pod @("curl", "-s", "--max-time", "60", "-b", "/tmp/jar-viva", "-w", " (HTTP %{http_code})", "-X", "POST", "-H", "Content-Type: application/json", "--data", $Body, "$Hub$Path")
    Write-Output "  $($out -join ' ')"
}
function Fault([string]$Svc, [string]$Body) {
    $out = Pod @("curl", "-s", "--max-time", "10", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST", "-H", "Content-Type: application/json", "--data", $Body, "http://$Svc.rpe-demo:8080/admin/faults")
    Write-Output "  fault on ${Svc}: HTTP $out"
}
function Look([string]$Text) { Write-Output ""; Write-Output "  -> Admin Panel: $Text" }

function Status {
    $s = C4 ""
    Write-Output "== Health (live)"
    foreach ($a in @($s.assessments | Where-Object { $_.source -eq "PROMETHEUS" } | Sort-Object target)) { Write-Output ("  {0,-18}{1}" -f $a.health, $a.target) }
    Write-Output "== Rate v2 per service (held changes are tried again every cycle)"
    foreach ($p in @($s.rateEvidence.PSObject.Properties)) {
        Write-Output "  $($p.Name): $($p.Value.diagnosis), rate $($p.Value.rate)"
        foreach ($h in @($p.Value.held)) { if ($h) { Write-Output "      $h" } }
    }
    Write-Output "== Latest automatic rate/retry changes (c4-auto)"
    $auto = @($s.changes | Where-Object { $_.reviewer -and $_.reviewer.StartsWith("c4-auto") } | Sort-Object { $_.proposal.generatedAt } -Descending | Select-Object -First 8)
    foreach ($c in $auto) { Write-Output "  $($c.proposal.action) $($c.proposal.target): $($c.proposal.currentValue) -> $($c.proposal.proposedValue)  $($c.reviewer)  $($c.delivery)" }
    Write-Output "== Cards waiting for a person (deadline and joint only)"
    $wait = @($s.changes | Where-Object { $_.decision -eq "PENDING_APPROVAL" -and $_.delivery -ne "SUPERSEDED" -and $_.delivery -ne "EXPIRED" -and $_.proposal.source -ne "MOCK" })
    if ($wait.Count -eq 0) { Write-Output "  (none)" }
    foreach ($c in $wait) { Write-Output "  $($c.proposal.action) $($c.proposal.target): $($c.proposal.currentValue) -> $($c.proposal.proposedValue)" }
}

switch ($Step) {
    "warmup" {
        Write-Output "== Traffic generator"
        & kubectl --context $Context -n rpe-e2e get deploy rpe-traffic -o "jsonpath={.status.readyReplicas}/{.spec.replicas} ready"; Write-Output ""
        Write-Output "== Telemetry"
        $conn = C4 "/connections"; foreach ($p in $conn.PSObject.Properties) { Write-Output "  $($p.Name): $($p.Value.state)" }
        Status
        Look "Recommendations -> Health: every service HEALTHY (a fresh C4 needs 5-7 minutes to learn); Telemetry: Live"
    }
    "lowrate" {
        $r = if ($Rate -gt 0) { $Rate } else { 1 }
        HubLogin
        Write-Output "== Leaky bucket of $Service to $r req/s (C3 rate-limit control, two-phase rollout)"
        HubPost "/api/hub/policies" "{`"control`":`"rate-limit`",`"service`":`"$Service`",`"ratePerSec`":$r,`"rollout`":`"two-phase`"}"
        Write-Output "  The traffic (~3 req/s) now exceeds the rate: the queue fills within 1-2 minutes (RATE_TOO_LOW: the app is fine)."
        Look "Health -> $Service; Reviewed -> rate raises approved by c4-auto (or 'held: ...' in Health while a deadline is not settled). No card waits for a person."
    }
    "overload" {
        Write-Output "== Fault on ${Service}: latency 500 ms, 10% errors"
        Fault $Service '{"latencyMs":500,"errorRate":0.1}'
        Look "Health -> $Service STRESSED/OVERLOADED; Reviewed -> cuts of at most -20% and retry -> 0 by c4-auto (never below the floor)."
    }
    "recover" {
        $r = if ($Rate -gt 0) { $Rate } else { 100 }
        Write-Output "== Fault off on $Service, rate back to $r req/s"
        Fault $Service '{}'
        HubLogin
        HubPost "/api/hub/policies" "{`"control`":`"rate-limit`",`"service`":`"$Service`",`"ratePerSec`":$r,`"rollout`":`"two-phase`"}"
        Look "Health -> $Service back to HEALTHY; Reviewed -> raises by c4-auto; a too-generous rate is lowered in -10% steps after generous-wait (2 min)."
    }
    "deadline" {
        HubLogin
        Write-Output "== Route deadline of GET /api/orders to $DeadlineMs ms (like e2e 95)"
        HubPost "/api/hub/policies" "{`"control`":`"chain-deadline`",`"service`":`"gateway`",`"route`":`"GET /api/orders`",`"deadlineMs`":$DeadlineMs,`"rollout`":`"two-phase`"}"
        Write-Output "  Requests are still served; they are signalled (cs=expired). C4 proposes a deadline increase after 1-3 minutes."
        Look "Approval queue -> a deadline card waiting for a person. Health -> rate changes on the chain are 'held: deadline not settled ..., trying again'."
    }
    "status" { Status }
    "toohigh" {
        $r = if ($Rate -gt 0) { $Rate } else { 200 }
        HubLogin
        Write-Output "== Leaky bucket of $Service to $r req/s: far above what the app can do once it burns CPU"
        HubPost "/api/hub/policies" "{`"control`":`"rate-limit`",`"service`":`"$Service`",`"ratePerSec`":$r,`"rollout`":`"two-phase`"}"
        Write-Output "== Fault on ${Service}: cpuBurnMs $BurnMs (each request burns CPU, so the app gets slower with more load)"
        Fault $Service "{`"cpuBurnMs`":$BurnMs}"
        Write-Output "== Load pod rpe-viva-load: 3 min busy ($Parallel parallel loops on GET /api/orders), 3 min quiet, repeating"
        & kubectl --context $Context -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=true | Out-Null
        $loop = 'while true; do end=$(($(date +%s) + 180)); i=0; while [ $i -lt PAR ]; do i=$((i+1)); (while [ $(date +%s) -lt $end ]; do curl -s -o /dev/null --max-time 5 http://gateway.rpe-demo:8080/api/orders; done) & done; wait; sleep 180; done'
        $loop = $loop.Replace("PAR", "$Parallel") # no double quotes inside, so Windows PowerShell passes it as one argument
        & kubectl --context $Context -n rpe-e2e run rpe-viva-load --image=curlimages/curl:8.11.1 --restart=Never --labels=app.kubernetes.io/part-of=rpe `
            --command -- /bin/sh -c $loop
        Write-Output "  Watch (refresh with: ... status), allow 10-15 minutes:"
        Write-Output "  1. quiet periods teach P0: Health evidence 'P0 ... ms from the N quietest of M windows'"
        Write-Output "  2. busy periods: $Service APP_OVERLOADED ('app time ... > 1.5 x P0' or 'app time grows with load'), not HEALTHY"
        Write-Output "  3. automatic cuts by c4-auto in steps of at most -20% (Reviewed tab), never a card for a person"
        Write-Output "  4. the rate settles below what $Service really managed: ceiling 0.9 x accepted req/s (remembered 10 min in the demo)"
        Write-Output "  Nothing happens? Raise the burn or the loops: ... toohigh -BurnMs 40 -Parallel 12"
        Look "Health -> $Service rate diagnosis and P0 evidence; Reviewed -> c4-auto cuts. Undo everything with: ... toohigh-stop"
    }
    "toohigh-stop" {
        $r = if ($Rate -gt 0) { $Rate } else { 100 }
        Write-Output "== Undo toohigh: load pod deleted, fault off on $Service, rate back to $r req/s"
        & kubectl --context $Context -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=false
        Fault $Service '{}'
        HubLogin
        HubPost "/api/hub/policies" "{`"control`":`"rate-limit`",`"service`":`"$Service`",`"ratePerSec`":$r,`"rollout`":`"two-phase`"}"
        Look "Health -> $Service back to HEALTHY within a few minutes (C4 keeps the learned P0; make reset-data starts fresh)."
    }
    "reset" {
        Write-Output "== Reset: faults off, then make reset-data"
        try { & kubectl --context $Context -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=false } catch {}
        foreach ($svc in "gateway", "order-svc", "inventory-svc", "payment-svc", "loyalty-svc", "shipping-svc", "fraud-check-svc") { try { Fault $svc '{}' } catch {} }
        Write-Output "  Now run in Git Bash from the repository root:  make reset-data"
        Look "a fresh C4 relearns its baselines (5-7 minutes); rate recommendations work without any C3 policy."
    }
    default { Get-Content $PSCommandPath -TotalCount 15 | Select-Object -Skip 1 }
}
