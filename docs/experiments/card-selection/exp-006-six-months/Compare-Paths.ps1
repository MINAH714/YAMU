param([Parameter(Mandatory=$true)][string]$RunPath)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $RunPath 'trades.zip'))
function Read-Path([string]$name) {
 $reader=[IO.StreamReader]::new($zip.GetEntry($name).Open())
 try {return ($reader.ReadToEnd() | ConvertFrom-Json -Depth 100)} finally {$reader.Dispose()}
}
try {
 $yamu=Read-Path 'YAMU.json'
 $summary=Get-Content (Join-Path $RunPath 'summary.json') -Raw | ConvertFrom-Json
 $best=$summary | Where-Object strategy -eq RANDOM | Sort-Object totalKrw -Descending | Select-Object -First 1
 $rows=@(foreach($name in @('FIXED_FIRST','FIXED_TOKTOK','CATEGORY',"RANDOM-$($best.seed)")) {
  $other=Read-Path "$name.json"; $delta=0L
  for($i=0;$i -lt $yamu.trades.Count;$i++) {
   $a=$yamu.trades[$i];$b=$other.trades[$i]
   if(($a.transaction | ConvertTo-Json -Compress) -ne ($b.transaction | ConvertTo-Json -Compress)){throw 'Different input'}
   $difference=[decimal]$a.benefitKrw-[decimal]$b.benefitKrw;$delta+=$difference
   [pscustomobject]@{comparison=$name;transaction=$a.transaction.number;at=([DateTimeOffset]$a.transaction.at).ToUniversalTime().ToString('o');purpose=$a.transaction.purpose;brand=$a.transaction.brand;amount=$a.transaction.amount;yamuCard=$a.selectedCard;otherCard=$b.selectedCard;yamuBenefit=$a.benefitKrw;otherBenefit=$b.benefitKrw;yamuMinusOther=$difference;yamuFirstPrevious=$a.previousPerformance[0];yamuToktokPrevious=$a.previousPerformance[1];otherFirstPrevious=$b.previousPerformance[0];otherToktokPrevious=$b.previousPerformance[1]}
  }
  if($delta -ne ([decimal]$yamu.total-[decimal]$other.total)){throw 'Difference aggregation mismatch'}
 })
 $rows | Export-Csv (Join-Path $RunPath 'transaction-differences.csv') -NoTypeInformation -Encoding utf8
 $rows | Group-Object comparison,{([DateTimeOffset]$_.at).ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM')} | ForEach-Object {
  [pscustomobject]@{comparison=$_.Group[0].comparison;month=([DateTimeOffset]$_.Group[0].at).ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM');yamuMinusOther=($_.Group | Measure-Object yamuMinusOther -Sum).Sum}
 } | Export-Csv (Join-Path $RunPath 'monthly-differences.csv') -NoTypeInformation -Encoding utf8
} finally {$zip.Dispose()}
