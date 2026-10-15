param([string]$BaseUrl = 'http://localhost:8090')

$ErrorActionPreference = 'Stop'
foreach ($name in @('healthy', 'stressed', 'overloaded')) {
    $body = Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name.request.json") -Raw
    $expected = Get-Content -LiteralPath (Join-Path $PSScriptRoot "$name.recommendations.json") -Raw | ConvertFrom-Json
    $actual = Invoke-RestMethod -Method Post `
        -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/recommendations" `
        -ContentType 'application/json' -Body $body -TimeoutSec 10
    if ($actual.schemaVersion -cne '1.0' -or $actual.mode -cne 'ADVISORY' -or
        $actual.status -cne $expected.status -or $actual.serviceName -cne $expected.serviceName) {
        throw "$name returned an unexpected recommendation envelope"
    }
    if (@($actual.recommendations).Count -ne @($expected.recommendations).Count) {
        throw "$name returned an unexpected action count"
    }
    for ($i = 0; $i -lt @($expected.recommendations).Count; $i++) {
        $received = $actual.recommendations[$i]
        $wanted = $expected.recommendations[$i]
        if ($received.action -cne $wanted.action -or $received.reason -cne $wanted.reason) {
            throw "$name returned an unexpected action or reason"
        }
        $receivedKeys = @($received.parameters.PSObject.Properties.Name | Sort-Object)
        $wantedKeys = @($wanted.parameters.PSObject.Properties.Name | Sort-Object)
        if (($receivedKeys -join ',') -cne ($wantedKeys -join ',')) {
            throw "$name returned unexpected parameter names"
        }
        foreach ($key in $wantedKeys) {
            if ($received.parameters.$key -ne $wanted.parameters.$key) {
                throw "$name returned an unexpected value for $key"
            }
        }
    }
    $generated = [DateTimeOffset]::Parse([string]$actual.generatedAt)
    $expires = [DateTimeOffset]::Parse([string]$actual.expiresAt)
    if (($expires - $generated).TotalSeconds -ne 300) {
        throw "$name returned an unexpected validity period"
    }
    Write-Output "PASS $name -> $($actual.status), $(@($actual.recommendations).Count) actions"
}
