param(
    [uri]$BaseUrl = 'http://localhost:8090',
    [ValidateRange(1, 86400)]
    [int]$WindowSeconds = 300
)

$ErrorActionPreference = 'Stop'
# Synthetic correlated evidence only; this script does not apply policies.
# For the accelerated demo server use -BaseUrl http://localhost:8091 -WindowSeconds 30.
if (-not $BaseUrl.IsLoopback -or $BaseUrl.Scheme -ne 'http' -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment) {
    throw 'BaseUrl must be a local HTTP server root, such as http://localhost:8090.'
}

$uri = "$($BaseUrl.AbsoluteUri.TrimEnd('/'))/api/v1/analyses/cancellations"
$windowEnd = [DateTimeOffset]::UtcNow.AddSeconds(-1)
$body = [ordered]@{
    chainId = 'demo-cancellation-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    services = @('order-service', 'payment-service')
    windowStart = $windowEnd.AddSeconds(-$WindowSeconds).ToString('o')
    windowEnd = $windowEnd.ToString('o')
    cancellationCount = 20
    continuedProcessingCount = 15
    downstreamActivityCount = 12
    observationGraceMs = 100
    telemetryComplete = $true
}

function Assert-Condition([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Check-Result([string]$status, [bool]$ready, [bool]$review, [int]$findingCount) {
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $uri `
        -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 4) -TimeoutSec 10
    $result = $response.Content | ConvertFrom-Json
    Assert-Condition ($response.StatusCode -eq 200 -and $result.schemaVersion -ceq '1.0' -and
        $result.mode -ceq 'ADVISORY' -and $result.chainId -ceq $body.chainId -and
        $result.status -ceq $status -and $result.ready -eq $ready -and
        $result.requiresReview -eq $review -and @($result.findings).Count -eq $findingCount -and
        -not [string]::IsNullOrWhiteSpace($result.message)) "Unexpected response for $status; check server window settings."
    Write-Host "PASS HTTP 200 $status ($findingCount findings)"
    return $result
}

$detected = Check-Result 'PROBLEM_DETECTED' $true $true 2
Assert-Condition ($detected.findings[0].code -ceq 'PROCESSING_AFTER_CANCELLATION' -and
    $detected.findings[0].affectedRequestCount -eq 15 -and
    $detected.findings[1].code -ceq 'DOWNSTREAM_CALL_AFTER_CANCELLATION' -and
    $detected.findings[1].affectedRequestCount -eq 12) 'Unexpected finding codes or affected counts.'
# The same cancelled request can appear in both counts; do not add them together.
$body.telemetryComplete = $false
Check-Result 'INSUFFICIENT_DATA' $false $false 0 | Out-Null
$body.telemetryComplete = $true
$body.continuedProcessingCount = 0
$body.downstreamActivityCount = 0
Check-Result 'NO_PROBLEM_OBSERVED' $true $false 0 | Out-Null
$body.cancellationCount = 0
Check-Result 'NO_CANCELLATIONS_OBSERVED' $true $false 0 | Out-Null
$body.windowEnd = $windowEnd.AddDays(-1).ToString('o')
$body.windowStart = $windowEnd.AddDays(-1).AddSeconds(-$WindowSeconds).ToString('o')
Check-Result 'WAITING_FOR_FRESH_DATA' $false $false 0 | Out-Null

$body.windowEnd = $windowEnd.ToString('o')
$body.windowStart = $windowEnd.AddSeconds(-$WindowSeconds).ToString('o')
$body.continuedProcessingCount = 1
try {
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri $uri -ContentType 'application/json' `
        -Body ($body | ConvertTo-Json -Depth 4) -TimeoutSec 10 | Out-Null
    throw 'Expected HTTP 400 for an affected count exceeding cancellationCount.'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 400) { throw }
    Write-Host 'PASS HTTP 400 for inconsistent counts (intentional invalid-input test)'
}

Write-Host 'All six cancellation checks passed. Example findings follow; no policy was applied.'
$detected | ConvertTo-Json -Depth 6
