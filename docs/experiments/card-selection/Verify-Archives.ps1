$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pairs = @(@{Zip='original-results.zip'; Manifest='original-checksums.json'}, @{Zip='source-overlay.zip'; Manifest='source-checksums.json'},
           @{Zip='raw-records.zip'; Manifest='raw-checksums.json'}, @{Zip='execution-source.zip'; Manifest='execution-source-checksums.json'})
$verified = 0
foreach ($pair in $pairs) {
    foreach ($archive in Get-ChildItem -LiteralPath $PSScriptRoot -Filter $pair.Zip -File -Recurse) {
        $expected = Get-Content -LiteralPath (Join-Path $archive.DirectoryName $pair.Manifest) -Raw | ConvertFrom-Json
        $zip = [IO.Compression.ZipFile]::OpenRead($archive.FullName)
        try {
            if ($zip.Entries.Count -ne $expected.Count) { throw "Entry count mismatch: $($archive.FullName)" }
            foreach ($row in $expected) {
                $entry = $zip.GetEntry($row.Path)
                if ($null -eq $entry) { throw "Missing entry: $($row.Path)" }
                $stream = $entry.Open()
                try { $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
                if ($actual -ne $row.SHA256) { throw "Hash mismatch: $($row.Path)" }
                $verified++
            }
        } finally { $zip.Dispose() }
    }
}
Write-Output "PASS: $verified archived files match SHA-256 manifests."
