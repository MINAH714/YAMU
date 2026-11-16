param([Parameter(Mandatory=$true)][string]$ExecutionPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'executions')) + [IO.Path]::DirectorySeparatorChar
$target = (Resolve-Path -LiteralPath $ExecutionPath).Path
if (-not $target.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Execution must be under this experiment executions directory.' }
$status = Get-Content -LiteralPath (Join-Path $target 'status.txt') -Raw
if (-not $status.StartsWith('PASS:')) { throw 'Only completed successful executions may be compacted. Preserve failures as-is.' }
$names = @('runs.json','transactions.csv','limits.csv','prepared-policies.json','coverage.json')
foreach ($phase in Get-ChildItem -LiteralPath $target -Directory) {
    $archive = Join-Path $phase.FullName 'raw-records.zip'
    if (Test-Path -LiteralPath $archive) { throw "Archive already exists: $archive" }
    $files = @($names | ForEach-Object { Get-Item -LiteralPath (Join-Path $phase.FullName $_) })
    $hashes = @($files | ForEach-Object { [pscustomobject]@{Path=$_.Name; Bytes=$_.Length; SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
    $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
    try { foreach ($file in $files) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $file.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null } }
    finally { $zip.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        foreach ($expected in $hashes) {
            $stream = $zip.GetEntry($expected.Path).Open()
            try { $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
            if ($actual -ne $expected.SHA256) { throw "Archive verification failed: $($expected.Path)" }
        }
    } finally { $zip.Dispose() }
    $hashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $phase.FullName 'raw-checksums.json') -Encoding utf8
    # Delete only these five verified new execution outputs, never the original experiment files.
    foreach ($file in $files) {
        $full = [IO.Path]::GetFullPath($file.FullName)
        if (-not $full.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected file path' }
        Remove-Item -LiteralPath $full
    }
    Write-Output "Verified and compacted $($phase.Name): $($files.Count) files"
}
