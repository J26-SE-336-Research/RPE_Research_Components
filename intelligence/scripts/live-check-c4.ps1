<#
READ-ONLY check of the running C4 (intelligence layer): only GET requests, nothing in the cluster is changed.
  GET /api/v2/intelligence/connections     Prometheus / Tempo reachable with live data?
  GET /api/v2/intelligence                 health label per service and chain, current recommendations and who approved them,
                                           rate v2 diagnosis per service, deadline v2 state per chain, joint cards
  GET /api/v2/intelligence/audit?limit=20  the last audit entries

  powershell -ExecutionPolicy Bypass -File intelligence\scripts\live-check-c4.ps1 -PortForward   # the kind cluster
  powershell -ExecutionPolicy Bypass -File intelligence\scripts\live-check-c4.ps1                # C4 at http://localhost:8090
#>
param([string]$Url = "http://localhost:8090", [switch]$PortForward, [string]$Context = "kind-rpe")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$pf = $null
if ($PortForward) {
    $pf = Start-Process kubectl -ArgumentList "--context", $Context, "-n", "rpe-system", "port-forward", "svc/rpe-intelligence", "18090:8090" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3; $Url = "http://localhost:18090"
}
$api = "$Url/api/v2/intelligence"
# Windows PowerShell 5.1 decodes JSON without a charset as Latin-1; read the bytes as UTF-8 instead
function Get-Json([string]$Uri) {
    $resp = Invoke-WebRequest -Method Get -Uri $Uri -UseBasicParsing -TimeoutSec 30
    [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
}
function Who($c) { switch ($c.decision) { "APPROVED" { "approved by $($c.reviewer)" } "PENDING_APPROVAL" { "waiting for a person" } default { $c.decision } } }

try {
    try { $conn = Get-Json "$api/connections" } catch { Write-Error "C4 is not reachable at $Url"; exit 1 }
    Write-Output "== Connections ($Url)"
    foreach ($p in $conn.PSObject.Properties) { Write-Output "  $($p.Name): $($p.Value.state)  ($($p.Value.detail))" }

    $s = Get-Json $api
    Write-Output "== Collection"
    Write-Output "  enabled: $($s.collection.enabled), delivery to C3: $($s.collection.deliveryConfigured), services: $(@($s.collection.configuredServices) -join ', ')"
    if (@($s.collection.problems).Count -gt 0) { Write-Output "  problems: $((@($s.collection.problems) | Select-Object -First 5) -join '; ')" }

    Write-Output "== Health (live assessments)"
    $live = @($s.assessments | Where-Object { $_.source -ne "MOCK" } | Sort-Object source, target)
    if ($live.Count -eq 0) { Write-Output "  (none yet: C4 is still learning, or collection is off)" }
    foreach ($a in $live) { Write-Output ("  {0,-18}{1,-11}{2}" -f $a.health, $a.source, $a.target) }

    Write-Output "== Rate v2 (per service)"
    $rates = @($s.rateEvidence.PSObject.Properties)
    if ($rates.Count -eq 0) { Write-Output "  (no rate decision yet)" }
    foreach ($r in $rates) {
        $v = $r.Value; $to = if ($v.proposed) { " -> $($v.proposed)" } else { "" }
        Write-Output "  $($r.Name): $($v.diagnosis)  rate $($v.rate)$to  ($($v.reason))"
    }

    Write-Output "== Deadline v2 (per chain)"
    $chains = @($s.deadlineEvidence.PSObject.Properties)
    if ($chains.Count -eq 0) { Write-Output "  (no chain analysed yet)" }
    foreach ($c in $chains) {
        $v = $c.Value; $to = if ($v.proposedMs) { " -> $($v.proposedMs) ms" } else { "" }
        Write-Output "  $($c.Name): $($v.state)  $($v.decisionCase) $($v.holdReason)  deadline $($v.currentMs) ms$to"
    }

    Write-Output "== Current recommendations (not superseded or expired)"
    $jointIds = @(); foreach ($j in @($s.joints)) { foreach ($p in @($j.parts)) { $jointIds += $p.proposalId } }
    $changes = @($s.changes | Where-Object { $_.delivery -ne "SUPERSEDED" -and $_.delivery -ne "EXPIRED" } | Select-Object -First 20)
    if ($changes.Count -eq 0) { Write-Output "  (none)" }
    foreach ($c in $changes) {
        $p = $c.proposal; $tag = if ($jointIds -contains $p.id) { "[joint] " } else { "" }
        Write-Output "  $tag$($p.action) $($p.target): $($p.currentValue) -> $($p.proposedValue)  $(Who $c)  delivery $($c.delivery)"
    }
    Write-Output "  joint cards: $(@($s.joints).Count)"

    $audit = Get-Json "$api/audit?limit=20"
    Write-Output "== Audit (last 20)"
    Write-Output "  persisted in PostgreSQL: $($audit.persistent)"
    foreach ($e in @($audit.entries)) {
        Write-Output ("  {0}  {1,-22}{2} {3}: {4} -> {5}  {6}  {7}" -f ([string]$e.at).Substring(0, [math]::Min(19, ([string]$e.at).Length)), $e.kind, $e.action, $e.target, $e.currentValue, $e.proposedValue, $e.decision, $e.actor)
    }
} finally { if ($pf) { Stop-Process -Id $pf.Id -ErrorAction SilentlyContinue } }
