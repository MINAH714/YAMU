param(
    [switch]$Reset,
    [switch]$CatalogOnly
)

$ErrorActionPreference = 'Stop'
$previousOutputEncoding = $OutputEncoding

Push-Location (Join-Path $PSScriptRoot '..')
try {
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    if ($Reset -and $CatalogOnly) { throw '-Reset과 -CatalogOnly는 함께 사용할 수 없습니다.' }
    docker compose up -d --wait mysql redis
    if ($LASTEXITCODE -ne 0) { throw '로컬 MySQL·Redis 실행 실패' }
    $seedArguments = @('--quiet', 'seedLocal')
    if ($Reset) { $seedArguments += '-PresetSeed' }
    if ($CatalogOnly) { $seedArguments += '-PcatalogOnlySeed' }
    & .\gradlew.bat @seedArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Seed 적재 실패. 로컬 MySQL과 seed/.env.seed.local 설정을 확인하세요.'
    }
    # The same PowerShell session can now start BE with these exact crypto keys.
    $settingsPath = Join-Path $PSScriptRoot '.env.seed.local'
    if (-not $CatalogOnly -and (Test-Path -LiteralPath $settingsPath)) {
        foreach ($line in (Get-Content -LiteralPath $settingsPath -Encoding utf8)) {
            if ($line -match '^(YAMU_(?:CRYPTO_[A-Z_0-9]+|JWT_SECRET))=(.*)$') {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            }
        }
    }
} finally {
    $OutputEncoding = $previousOutputEncoding
    Pop-Location
}
