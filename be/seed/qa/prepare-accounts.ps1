#requires -Version 7.0
<# Explicit preparation only. Inherit approved DB/crypto environment; never pass secrets as arguments. #>
param(
    [Parameter(Mandatory)][string]$JarPath,
    [Parameter(Mandatory)][string]$DeviceManifest,
    [Parameter(Mandatory)][string]$ReceiptPath,
    [string]$SeedDirectory = $PSScriptRoot,
    [string]$JavaPath = 'java'
)
$ErrorActionPreference = 'Stop'
$pinInputs = @{}
$process = $null
try {
    if ($env:BILLING_JOBS_ENABLED -cne 'false' -or $env:QA_SEED_MAINTENANCE -cne 'true' -or
        [string]::IsNullOrWhiteSpace($env:QA_ACCOUNT_PREPARE_APPROVAL)) {
        throw 'Approved environment is required.'
    }
    $jar = (Resolve-Path -LiteralPath $JarPath).Path
    $manifest = (Resolve-Path -LiteralPath $DeviceManifest).Path
    $seed = (Resolve-Path -LiteralPath $SeedDirectory).Path
    $receipt = [IO.Path]::GetFullPath($ReceiptPath)
    if (Test-Path -LiteralPath $receipt) { throw 'Receipt must be a new file.' }
    foreach ($dataset in @('qa-550-life-1', 'qa-550-life-2', 'qa-550-life-3')) {
        $first = Read-Host "$dataset PIN" -AsSecureString
        $second = Read-Host "$dataset PIN 확인" -AsSecureString
        $firstPtr = [IntPtr]::Zero
        $secondPtr = [IntPtr]::Zero
        try {
            $firstPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($first)
            $secondPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($second)
            $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($firstPtr)
            if ($value -cne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secondPtr)) {
                throw 'PIN confirmation mismatch.'
            }
            $pinInputs[$dataset] = $value
        } finally {
            if ($firstPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstPtr) }
            if ($secondPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondPtr) }
            $first.Dispose()
            $second.Dispose()
            $value = $null
        }
    }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $JavaPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $start.Environment['QA_ACCOUNT_PINS_STDIN'] = 'true'
    foreach ($argument in @('-Dfile.encoding=UTF-8', '-Duser.timezone=UTC',
        '-Dloader.main=com.yamu.be.seed.qa.QaAccountPrepareCli', '-cp', $jar,
        'org.springframework.boot.loader.launch.PropertiesLauncher', 'prepare', $seed, $manifest, $receipt)) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $null = $process.Start()
    $output = $process.StandardOutput.ReadToEndAsync()
    $errors = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write(($pinInputs | ConvertTo-Json -Compress))
    $process.StandardInput.Close()
    $pinInputs.Clear()
    $process.WaitForExit()
    # The CLI emits IDs/fingerprints and safe error codes only. PINs never enter these streams.
    [Console]::Out.Write($output.GetAwaiter().GetResult())
    [Console]::Error.Write($errors.GetAwaiter().GetResult())
    $status = $process.ExitCode
} catch {
    # Do not format exception/input objects: their values may contain credentials.
    [Console]::Error.WriteLine('계정 준비를 완료하지 못했습니다. 입력·승인 환경과 CLI 오류 코드를 확인하세요.')
    $status = 1
} finally {
    $pinInputs.Clear()
    if ($null -ne $process) { $process.Dispose() }
}
exit $status
