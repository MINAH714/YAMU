param([string]$BaseUrl = 'http://127.0.0.1:18553')
$ErrorActionPreference = 'Stop'
if ($BaseUrl -ne 'http://127.0.0.1:18553') { throw 'This check only supports the isolated local app.' }
$deviceKey = [Security.Cryptography.ECDsa]::Create()
try {
    $deviceKey.GenerateKey([Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP256'))
    $device = Invoke-RestMethod "$BaseUrl/local/bootstrap-device" -Method Post -ContentType 'application/json' -Body (@{
        deviceId = [guid]::NewGuid().ToString()
        publicKey = [Convert]::ToBase64String($deviceKey.ExportSubjectPublicKeyInfo())
    } | ConvertTo-Json)
    $challenge = (Invoke-RestMethod "$BaseUrl/api/auth/login/challenge" -Method Post -ContentType 'application/json' -Body (@{userDeviceId=$device.userDeviceId}|ConvertTo-Json)).data
    $signature = [Convert]::ToBase64String($deviceKey.SignData([Text.Encoding]::UTF8.GetBytes($challenge.challenge),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence))
    $login = (Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (@{
        userDeviceId=$device.userDeviceId; challengeId=$challenge.challengeId; method='PIN'; signature=$signature; pin='493827'
    }|ConvertTo-Json)).data
    $headers = @{Authorization='Bearer '+$login.accessToken}
    $results = [Collections.Generic.List[object]]::new()
    foreach($path in @('/api/home','/api/users/me','/api/accounts','/api/cards','/api/notifications',
        '/api/users/me/card-performances','/api/benefit-limits','/api/forecasts/settings',
        '/api/forecasts/balance?accountIds=1,2,3&until=DAYS&days=30',
        '/api/reports/monthly?yearMonth=2026-11','/api/payments?yearMonth=2026-11&page=1&size=100')) {
        $response=Invoke-RestMethod ($BaseUrl+$path) -Headers $headers
        if(!$response.success){throw "Failed: $path"}
        $json=$response.data | ConvertTo-Json -Depth 50 -Compress
        if($json -match '"(?:title|body|memo|nickname|deviceName|accountName|label|name)"\s*:\s*"[^"]*(?:\bQA\b|\bp[0-9]+\b|테스트|더미)[^"]*"') {throw "Fixture label in $path"}
        $results.Add([PSCustomObject]@{endpoint=$path;status=200})
    }
    $accounts=(Invoke-RestMethod "$BaseUrl/api/accounts" -Headers $headers).data.accounts
    if(($accounts | Measure-Object balance -Sum).Sum -ne 2250000){throw 'Starting balance mismatch'}
    foreach($pair in @(@('004','0001'),@('088','0002'),@('003','0003'))) {
        if(($accounts | Where-Object {$_.bank.code -eq $pair[0]}).maskedAccountNo -notlike ('*'+$pair[1])){throw 'Account suffix mismatch'}
    }
    for($month=[datetime]'2025-12-01';$month -le [datetime]'2026-11-01';$month=$month.AddMonths(1)) {
        $ym=$month.ToString('yyyy-MM')
        $flow=(Invoke-RestMethod "$BaseUrl/api/asset-flows?yearMonth=$ym&accountIds=1,2,3" -Headers $headers).data
        if(@($flow.partialErrors).Count -ne 0){throw "Asset flow partial error: $ym"}
        if(@($flow.series|Where-Object {$_.date -lt '2026-11-16' -and $_.dataStatus -ne 'AVAILABLE'}).Count -ne 0){throw "Missing historical day: $ym"}
        $results.Add([PSCustomObject]@{endpoint="/api/asset-flows?yearMonth=$ym";status=200})
    }
    $sync=(Invoke-RestMethod "$BaseUrl/api/sync" -Headers $headers -Method Post -ContentType 'application/json' -Body '{}').data
    if(@($sync.failed).Count -ne 0){throw 'Local sync failed'}
    $results.Add([PSCustomObject]@{endpoint='POST /api/sync';status=200})
    $settings=(Invoke-RestMethod "$BaseUrl/api/forecasts/settings" -Headers $headers).data
    # Write the same threshold: tests the real PATCH route without changing the scenario.
    $response=Invoke-RestMethod "$BaseUrl/api/forecasts/settings" -Headers $headers -Method Patch -ContentType 'application/json' -Body (@{alertThreshold=$settings.alertThreshold}|ConvertTo-Json)
    if(!$response.success){throw 'Settings PATCH failed'}
    $results.Add([PSCustomObject]@{endpoint='PATCH /api/forecasts/settings';status=200})
    $results | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot '../../build/local-app/http-checks.json') -Encoding utf8
    Write-Output "$($results.Count) real HTTP checks passed; local balance 2250000; suffixes 0001/0002/0003."
} finally {
    $deviceKey.Dispose()
}
