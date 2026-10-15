param(
    [uri]$BaseUrl = 'http://localhost:8090',
    [int]$WindowSeconds = 300
)

$ErrorActionPreference = 'Stop'
# Synthetic chain telemetry only. With the accelerated demo server, use
# -BaseUrl http://localhost:8091 -WindowSeconds 30.
if (-not $BaseUrl.IsLoopback -or $BaseUrl.Scheme -ne 'http' -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment -or $WindowSeconds -le 0) {
    throw 'Use a local HTTP server root and a positive window duration.'
}
$windowEnd = [DateTimeOffset]::UtcNow.AddSeconds(-1)
$body = [ordered]@{
    chainId = 'order-payment-demo'
    services = @('order-service', 'payment-service')
    chainHealth = 'STRESSED'
    windowStart = $windowEnd.AddSeconds(-$WindowSeconds).ToString('o')
    windowEnd = $windowEnd.ToString('o')
    requestCount = 1000
    deadlineFailureCount = 20
    p99LatencyMs = 1200
    currentDeadlineMs = 1000
}
$uri = "$($BaseUrl.AbsoluteUri.TrimEnd('/'))/api/v1/recommendations/deadlines"
$result = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' `
    -Body ($body | ConvertTo-Json -Depth 4) -TimeoutSec 10
if ($result.status -ne 'READY' -or $result.proposedDeadlineMs -ne 1440 -or
    $result.mode -ne 'ADVISORY' -or $result.requiresApproval -ne $true) {
    throw "Unexpected deadline result; check window and policy settings: $($result | ConvertTo-Json -Depth 6)"
}
Write-Output 'PASS chain p99 1200 ms -> proposed deadline 1440 ms; human approval required'
$body.deadlineFailureCount = 0
$unchanged = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' `
    -Body ($body | ConvertTo-Json -Depth 4) -TimeoutSec 10
if ($unchanged.status -ne 'NO_CHANGE' -or $null -ne $unchanged.proposedDeadlineMs) {
    throw 'A deadline increase was suggested without deadline-failure evidence.'
}
Write-Output 'PASS no deadline failures -> no deadline increase'
$result | ConvertTo-Json -Depth 6
