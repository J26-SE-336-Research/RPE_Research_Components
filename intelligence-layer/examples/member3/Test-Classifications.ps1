param(
    [string]$BaseUrl = 'http://localhost:8090'
)

$ErrorActionPreference = 'Stop'

# These requests only classify synthetic metrics. No service policies are applied.
# Response files are captured examples; assessedAt changes on every live request.
foreach ($name in @('healthy', 'stressed', 'overloaded')) {
    $body = Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name.request.json") -Raw
    $expected = Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name.response.json") -Raw | ConvertFrom-Json
    $actual = Invoke-RestMethod -Method Post `
        -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/classifications" `
        -ContentType 'application/json' -Body $body -TimeoutSec 10

    if ($actual.serviceName -cne $expected.serviceName -or $actual.status -cne $expected.status) {
        throw "$name returned unexpected serviceName or status: $($actual | ConvertTo-Json -Depth 4 -Compress)"
    }
    $actualEvidence = ConvertTo-Json -InputObject @($actual.evidence) -Compress
    $expectedEvidence = ConvertTo-Json -InputObject @($expected.evidence) -Compress
    if ($actualEvidence -cne $expectedEvidence) {
        throw "$name returned unexpected evidence: $actualEvidence"
    }
    $assessedAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$actual.assessedAt, [ref]$assessedAt)) {
        throw "$name returned a missing or invalid assessedAt timestamp"
    }
    Write-Output "PASS $name -> $($actual.status)"
}
