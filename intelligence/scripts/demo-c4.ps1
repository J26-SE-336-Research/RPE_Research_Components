<#
Runs every C4 demo scenario (POST /api/v2/intelligence/demo/{scenario}) and prints a short summary per recommendation:
scenario, recommendation, current -> proposed, approved by (c4-auto / simulated-reviewer / waiting for a person), reason.
The rate and retry scenarios are run twice: as a reviewer would see them, and with ?mode=auto (automatic approval on).
Every scenario runs in an isolated engine inside C4; nothing is changed in the cluster and nothing is sent to Component 3.

  powershell -ExecutionPolicy Bypass -File intelligence\scripts\demo-c4.ps1                    # C4 at http://localhost:8090
  powershell -ExecutionPolicy Bypass -File intelligence\scripts\demo-c4.ps1 -PortForward       # the kind cluster's C4
  powershell -ExecutionPolicy Bypass -File intelligence\scripts\demo-c4.ps1 -Only joint-recovery
C4 must allow simulations (RRPE_MOCK_ENABLED=true; set in the demo cluster).
#>
param([string]$Url = "http://localhost:8090", [switch]$PortForward, [string]$Only = "", [string]$Context = "kind-rpe")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$pf = $null
if ($PortForward) {
    $pf = Start-Process kubectl -ArgumentList "--context", $Context, "-n", "rpe-system", "port-forward", "svc/rpe-intelligence", "18090:8090" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3; $Url = "http://localhost:18090"
}
$api = "$Url/api/v2/intelligence"
$old = "healthy", "stressed", "overloaded", "retry-stressed", "recovery", "deadline-increase", "deadline-recovery", "multiple-chains"
$deadline = "deadline-too-long-under-overload", "deadline-too-short-day-one", "services-slowing", "queue-rate-not-deadline", "mixed-paths", "rejecting-middleware-504"
$rate = "rate-too-low", "rate-too-low-day-one", "rate-real-overload", "rate-recovery", "rate-short-burst", "rate-bursts", "retry-storm-rate"
$joint = "joint-recovery", "joint-capacity-drop"

# Windows PowerShell 5.1 decodes JSON without a charset as Latin-1; read the bytes as UTF-8 instead
function Get-Json([string]$Method, [string]$Uri) {
    $resp = Invoke-WebRequest -Method $Method -Uri $Uri -UseBasicParsing -TimeoutSec 120
    [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}
function Num($v) { if ($null -eq $v) { "?" } elseif ([math]::Abs($v) -ge 1) { [string][math]::Round([double]$v, 1) } else { [string][math]::Round([double]$v, 3) } }
function Unit($a) { switch ($a) { "LEAKY_BUCKET" { " req/s" } "CHAIN_DEADLINE" { " ms" } default { "" } } }
function Name($a) { switch ($a) { "LEAKY_BUCKET" { "rate" } "RETRY_BUDGET" { "retry ratio" } "CHAIN_DEADLINE" { "deadline" } default { $a } } }
function Short($t) { $t = ([string]$t) -replace "\s+", " "; if ($t.Length -gt 110) { $t.Substring(0, 107) + "..." } else { $t } }
function Who($c) {
    switch ($c.decision) { "APPROVED" { "approved by $($c.reviewer)" } "PENDING_APPROVAL" { "waiting for a person" } "REJECTED" { "rejected" } default { $c.decision } }
}

function Run-Scenario([string]$s, [bool]$auto) {
    $q = ""; $label = $s
    if ($auto) { $q = "?mode=auto"; $label = "$s ?mode=auto" }
    Write-Output "== $label"
    try { $r = Get-Json Post "$api/demo/$s$q" }
    catch { Write-Output "    error: $($_.Exception.Message)"; return }
    $jointIds = @(); foreach ($j in @($r.joints)) { foreach ($p in @($j.parts)) { $jointIds += $p.proposalId } }
    if ($r.rateEvidence) {
        $e = $r.rateEvidence[0]; $steps = @($e.steps)
        if ($steps.Count -eq 0) { Write-Output "    no change   (final rate $(Num $e.finalRate) req/s, shed $(Num $e.shedAtEnd) req/s)"; return }
        foreach ($st in $steps) {
            $by = if ($st.reviewer -eq "pending") { "waiting for a person" } else { "approved by $($st.reviewer)" }
            Write-Output ("    {0} {1} -> {2}{3}   {4}   [{5}]   {6}" -f (Name $st.action), (Num $st.from), (Num $st.to), (Unit $st.action), $by, (Short $st.phase), (Short $st.evidence))
        }
        Write-Output "    final rate $(Num $e.finalRate) req/s, lowest $(Num $e.lowestRate), retry ratio $(Num $e.finalRetry), shed at the end $(Num $e.shedAtEnd) req/s"
        return
    }
    $changes = @($r.changes | Where-Object { $_.delivery -ne "SUPERSEDED" -and $_.delivery -ne "EXPIRED" })
    if ($changes.Count -eq 0) {
        $why = ""; foreach ($d in @($r.deadlineEvidence)) { if ($d.evidence) { $why += " ($($d.evidence.decisionCase) $($d.evidence.holdReason), lever $($d.evidence.lever), state $($d.evidence.state))" } }
        Write-Output "    no change$why"; return
    }
    foreach ($c in $changes) {
        $p = $c.proposal; $isJoint = $jointIds -contains $p.id
        $reason = if ($isJoint) { if ($p.action -eq "CHAIN_DEADLINE") { $p.evidence[1] } else { $p.evidence[0] } }
                  elseif ($p.action -eq "CHAIN_DEADLINE") { $p.evidence[0] } else { $p.evidence[-1] }
        $tag = if ($isJoint) { "[joint] " } else { "" }
        Write-Output ("    {0}{1} {2}: {3} -> {4}{5}   {6}   {7}" -f $tag, (Name $p.action), ($p.target -split " \| ")[0], (Num $p.currentValue), (Num $p.proposedValue), (Unit $p.action), (Who $c), (Short $reason))
    }
}

try {
    try { Invoke-RestMethod -Uri "$Url/actuator/health" -TimeoutSec 10 | Out-Null } catch { Write-Error "C4 is not reachable at $Url"; exit 1 }
    Write-Output "C4 demo scenarios at $Url (isolated simulations; nothing is sent to Component 3)"
    foreach ($s in ($old + $deadline + $rate + $joint)) {
        if ($Only -and $s -ne $Only) { continue }
        Run-Scenario $s $false
        if ($rate -contains $s) { Run-Scenario $s $true }
    }
} finally { if ($pf) { Stop-Process -Id $pf.Id -ErrorAction SilentlyContinue } }
