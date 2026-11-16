$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$mobile = Join-Path $root 'fe/android/app/src'
$debug = Get-Content (Join-Path $mobile 'debug/java/com/yamu/app/data/DataModule.kt') -Raw
$release = Get-Content (Join-Path $mobile 'release/java/com/yamu/app/data/DataModule.kt') -Raw
$pattern = 'abstract fun (\w+)\(impl: (\w+)\): (\w+)'
$debugBindings = @([regex]::Matches($debug, $pattern) | ForEach-Object { $_.Value } | Sort-Object)
$releaseBindings = @([regex]::Matches($release, $pattern) | ForEach-Object { $_.Value } | Sort-Object)
if ($debugBindings.Count -ne 12 -or (Compare-Object $debugBindings $releaseBindings)) {
    throw 'Debug/release repository bindings differ. Review before using the local app as a production preview.'
}
if (($debugBindings -join "`n") -match 'Fake|Mock|Stub') { throw 'A fake repository is bound.' }
$harness = Get-Content (Join-Path $root 'be/src/test/java/com/yamu/be/seed/qa/QaLocalAppTest.java') -Raw
$overrides = @([regex]::Matches($harness, '@MockitoBean\s+([\w.]+)\s+\w+;') | ForEach-Object { $_.Groups[1].Value.Split('.')[-1] } | Sort-Object)
$allowed = @('Clock', 'FinApiClient', 'SsafyAccountApi', 'SsafyTransferApi') | Sort-Object
if (Compare-Object $overrides $allowed) { throw 'Local service overrides changed; inspect for fabricated API responses.' }
Write-Output 'PASS: 12 debug/release repository bindings match; local overrides are limited to clock and financial adapters.'
Write-Output 'This source check does not assert the deployed server version, seed freshness, AI connectivity, or financial behavior.'
