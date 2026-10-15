param(
    [switch]$StartServer,
    [uri]$BaseUrl = 'http://localhost:8091',
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

# This demo replays synthetic observations with short learning windows.
# It changes in-memory demo data only; it does not approve or apply policies.
# Terminal 1: run this script with -StartServer and leave it running.
# Terminal 2: run this script without -StartServer to perform the checks.
if (-not $BaseUrl.IsLoopback -or $BaseUrl.Scheme -ne 'http' -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment) {
    throw 'BaseUrl must be a local HTTP server root, such as http://localhost:8091.'
}

if ($StartServer) {
    $modulePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $serverOptions = @(
        "--server.port=$($BaseUrl.Port)",
        '--logging.level.root=WARN', '--debug=false',
        '--rrpe.baseline.recent-window=30s',
        '--rrpe.baseline.analysis-interval=15s',
        '--rrpe.baseline.sample-interval=10s',
        '--rrpe.baseline.baseline-window=1m',
        '--rrpe.baseline.startup-exclusion=20s',
        '--rrpe.baseline.max-sample-gap=20s',
        '--rrpe.baseline.minimum-recent-samples=2',
        '--rrpe.baseline.minimum-baseline-samples=4'
    )
    Write-Output "Starting a local demo server at $BaseUrl with accelerated learning settings."
    Write-Output 'Leave this terminal open, run the script in another terminal, and press Ctrl+C here when finished.'
    Push-Location $modulePath
    try {
        & .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=$($serverOptions -join ' ')"
        if ($LASTEXITCODE -ne 0) { throw 'Demo server exited with an error. Check JAVA_HOME, Maven output, and whether the port is already in use.' }
    } finally {
        Pop-Location
    }
    return
}

$apiUrl = "$($BaseUrl.AbsoluteUri.TrimEnd('/'))/api/v1/metrics"
$serviceName = 'demo-order-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$origin = [DateTimeOffset]::UtcNow.AddSeconds(-110)

function Assert-Condition([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Assert-Waiting([string]$expectedStatus) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$apiUrl/$serviceName/recommendations" -TimeoutSec 10
    Assert-Condition ($response.StatusCode -eq 200) 'Waiting should return HTTP 200.'
    $state = $response.Content | ConvertFrom-Json
    Assert-Condition ($state.ready -eq $false -and $state.status -ceq $expectedStatus -and
        @($state.recommendations).Count -eq 0 -and -not [string]::IsNullOrWhiteSpace($state.message)) 'Unexpected readiness response.'
}

function Send-Observation([int]$offset, [bool]$degraded) {
    $body = [ordered]@{
        serviceName = $serviceName
        collectedAt = $origin.AddSeconds($offset).ToString('o')
        requestRate = 100
        p95LatencyMs = 100
        errorRate = 0
        retryCount = 0
        rejectedRequestCount = 0
        deadlineFailureCount = 0
        cancellationCount = 0
        inFlightRequests = 20
        successfulThroughput = 100
    }
    if ($degraded) {
        $body.requestRate = 150
        $body.p95LatencyMs = 250
        $body.errorRate = 0.12
        $body.retryCount = 30
        $body.inFlightRequests = 70
        $body.successfulThroughput = 90
    }
    return Invoke-RestMethod -Method Post -Uri "$apiUrl/observations" `
        -ContentType 'application/json' -Body ($body | ConvertTo-Json) -TimeoutSec 10
}

Write-Output "Testing synthetic service $serviceName at $BaseUrl. This is a functional demo, not a load test."
Assert-Waiting 'WAITING_FOR_OBSERVATIONS'
$progress = Send-Observation 0 $false
Assert-Condition ($progress.phase -eq 'STARTUP') 'Expected startup exclusion.'
Assert-Waiting 'LEARNING_BASELINE'
Write-Output 'PASS startup returns a normal learning status'

foreach ($offset in @(10, 20, 30, 40, 50, 60, 70)) {
    $progress = Send-Observation $offset $false
}
Assert-Condition ($progress.phase -eq 'READY') 'Baseline is not ready. Start the server using this script with -StartServer to enable the demo settings.'
Assert-Condition ($progress.baseline.p95LatencyMs -eq 100 -and $progress.baseline.requestRate -eq 100) 'Unexpected learned baseline.'
Assert-Waiting 'WAITING_FOR_RECENT_DATA'
Write-Output 'PASS stable observations learned a baseline; waiting for recent data returns a normal status'

foreach ($offset in @(80, 90, 100, 110)) {
    $progress = Send-Observation $offset $true
}
$window = Invoke-RestMethod -Uri "$apiUrl/$serviceName/recent-window" -TimeoutSec 10
Assert-Condition ($window.phase -eq 'READY') 'Recent window is not ready; check demo settings and telemetry freshness.'
Assert-Condition ($window.latencyAggregation -eq 'MAX_OBSERVED_P95' -and $window.summary.p95LatencyMs -eq 250) 'Unexpected recent latency summary.'
Assert-Condition ($progress.baseline.p95LatencyMs -eq 100) 'Degraded observations changed the baseline.'
Write-Output 'PASS degraded observations formed a recent window without changing the baseline'

$plan = Invoke-RestMethod -Uri "$apiUrl/$serviceName/recommendations" -TimeoutSec 10
Assert-Condition ($plan.schemaVersion -eq '1.0' -and $plan.mode -eq 'ADVISORY' -and
    $plan.ready -eq $true -and $plan.serviceName -eq $serviceName -and $plan.status -eq 'OVERLOADED') 'Unexpected recommendation envelope.'
$expectedActions = @('DISABLE_RETRIES', 'RATE_LIMIT')
$actualActions = @($plan.recommendations | ForEach-Object { $_.action } | Sort-Object)
Assert-Condition (($actualActions -join ',') -ceq ($expectedActions -join ',')) 'Unexpected recommendation actions.'
$rateLimit = $plan.recommendations | Where-Object { $_.action -eq 'RATE_LIMIT' }
$retries = $plan.recommendations | Where-Object { $_.action -eq 'DISABLE_RETRIES' }
Assert-Condition ($rateLimit.parameters.maxRequestsPerSecond -eq 75 -and
    $retries.parameters.maxAttempts -eq 1) 'Unexpected recommendation parameters.'
Assert-Condition (([DateTimeOffset]::Parse([string]$plan.expiresAt) -
    [DateTimeOffset]::Parse([string]$plan.generatedAt)).TotalSeconds -eq 300) 'Unexpected plan validity period.'
Write-Output 'PASS learned baseline + recent window -> OVERLOADED recommendations'

$sampleCount = $window.sampleCount
$replay = Send-Observation 110 $true
$window = Invoke-RestMethod -Uri "$apiUrl/$serviceName/recent-window" -TimeoutSec 10
Assert-Condition ($window.sampleCount -eq $sampleCount) 'Identical replay was counted twice.'
Write-Output 'PASS identical replay does not add samples'

$json = $plan | ConvertTo-Json -Depth 8
if ($OutputPath) {
    Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8
    Write-Output "Saved recommendation example to $OutputPath. It uses a synthetic service name and expires after 5 minutes."
}
Write-Output $json
