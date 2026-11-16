param([Parameter(Mandatory=$true)][string]$RunPath)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if ((Get-Content (Join-Path $RunPath 'status.txt') -Raw) -ne 'SUCCESS') { throw 'Not a successful run' }
$zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $RunPath 'trades.zip'))
$verified=0; $evaluations=0
$expectedCount=(Get-Content (Join-Path $RunPath 'transactions.json') -Raw | ConvertFrom-Json).Count
try {
 foreach($entry in $zip.Entries) {
  $reader=[IO.StreamReader]::new($entry.Open())
  try { $run=$reader.ReadToEnd() | ConvertFrom-Json -Depth 100 } finally { $reader.Dispose() }
  $previous=@(400000L,400000L); $performance=@(0L,0L); $spending=@(0L,0L)
  $used=@(0L,0L); $daily=@{}; $month=''; $total=0L; $monthTotal=0L; $monthly=@()
  [decimal]$randomState=([long]$run.seed -bxor 25214903917L) -band 281474976710655L
  foreach($trade in $run.trades) {
   $p=$trade.transaction; $date=([DateTimeOffset]$p.at).ToOffset([TimeSpan]::FromHours(9)); $next=$date.ToString('yyyy-MM'); $day=$date.ToString('yyyy-MM-dd')
   if($month -ne $next) {
    if($month) {
     $monthly+=@{month=$month;total=$monthTotal;performance=$performance.Clone();previous=$previous.Clone();spending=$spending.Clone()}
     $previous=$performance.Clone()
    }
    $performance=@(0L,0L);$spending=@(0L,0L);$used=@(0L,0L);$monthTotal=0L;$month=$next
   }
   $cap=if($previous[0] -ge 1000000){20000L}elseif($previous[0] -ge 500000){10000L}elseif($previous[0] -ge 300000){5000L}else{0L}
   $first=0L; $toktok=0L
   $firstTarget=$p.category -in @('GENERAL_RESTAURANT','COFFEE_SHOP','CONVENIENCE_STORE') -or ($p.category -eq 'ONLINE_MARKETPLACE' -and $p.brand -eq 'COUPANG')
   if($previous[0] -ge 300000 -and $firstTarget) {
    $first=[long][Math]::Floor([Math]::Min([Math]::Min([decimal]$p.amount/20,500),[Math]::Min(1000-[long]$daily[$day],$cap-$used[0])))
   }
   if($previous[1] -ge 300000 -and $p.brand -eq 'STARBUCKS') { $toktok=[long][Math]::Min([decimal]$p.amount/2,10000-$used[1]) }
   $expected=@($first,$toktok)
   $recognized=@([long]$p.amount,$(if($toktok -gt 0){0L}else{[long]$p.amount}))
   for($i=0;$i -lt 2;$i++) {
    if([decimal]$trade.candidateEvaluations[$i].calculation.netBenefit -ne $expected[$i]) { throw "Independent benefit mismatch: $($entry.Name) transaction $($p.number) card $i" }
    if($trade.previousPerformance[$i] -ne $previous[$i] -or $trade.performanceBefore[$i] -ne $performance[$i] -or $trade.candidateRecognizedAmounts[$i] -ne $recognized[$i]) { throw 'Performance mismatch' }
    $evaluations++
   }
   $chosen=switch($run.strategy) {
    'FIXED_FIRST' {0} 'FIXED_TOKTOK' {1} 'CATEGORY' {if($p.category -eq 'COFFEE_SHOP'){1}else{0}}
    'YAMU' {if($toktok -gt $first){1}else{0}}
    'RANDOM' { $randomState=($randomState*25214903917+11)%281474976710656; if($randomState -ge 140737488355328){1}else{0} }
    default {throw 'Unknown strategy'}
   }
   if($chosen -ne $trade.selectedIndex -or [decimal]$trade.benefitKrw -ne $expected[$chosen]) { throw 'Selection or settled benefit mismatch' }
   $value=$expected[$chosen]; $used[$chosen]+=$value
   if($chosen -eq 0){$daily[$day]=[long]$daily[$day]+$value}
   $performance[$chosen]+=$recognized[$chosen];$spending[$chosen]+=[long]$p.amount;$total+=$value;$monthTotal+=$value
   for($i=0;$i -lt 2;$i++) {if($trade.performanceAfter[$i] -ne $performance[$i] -or $trade.spentThisMonthAfter[$i] -ne $spending[$i]){throw 'Post-state mismatch'}}
   foreach($change in $trade.limitChanges) {
    if([long]$change.usedBefore+[long]$change.consumed -ne [long]$change.usedAfter -or $change.usedAfter -gt $change.maximum) {throw 'Limit conservation mismatch'}
   }
  }
  $monthly+=@{month=$month;total=$monthTotal;performance=$performance.Clone();previous=$previous.Clone();spending=$spending.Clone()}
  if($run.trades.Count -ne $expectedCount -or $monthly.Count -ne 6 -or [decimal]$run.total -ne $total) {throw 'Run totals mismatch'}
  for($m=0;$m -lt 6;$m++) {
   if([decimal]$run.months[$m].totalKrw -ne $monthly[$m].total -or $run.months[$m].month -ne $monthly[$m].month) {throw 'Monthly total mismatch'}
   for($i=0;$i -lt 2;$i++) {if($run.months[$m].recognizedPerformance[$i] -ne $monthly[$m].performance[$i] -or $run.months[$m].previousPerformance[$i] -ne $monthly[$m].previous[$i]){throw 'Monthly performance mismatch'}}
  }
  $verified++
 }
} finally {$zip.Dispose()}
if($verified -ne 104 -or $evaluations -ne 104*$expectedCount*2) {throw 'Coverage mismatch'}
$manifest=Get-Content (Join-Path $RunPath 'manifest.json') -Raw | ConvertFrom-Json
$sourceZip=[IO.Compression.ZipFile]::OpenRead((Join-Path $RunPath 'source-snapshot.zip'))
try {
 foreach($entry in $sourceZip.Entries) {
  $stream=$entry.Open();try{$actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
  if($actual -ne $manifest.files.($entry.FullName)){throw 'Source archive hash mismatch'}
 }
}finally{$sourceZip.Dispose()}
@{status='PASS';runs=$verified;candidateArithmeticChecks=$evaluations;stateAndRandomMapping='PASS';sourceArchive='PASS';method='Independent fixed-scope formula, monthly tiers and discount-recognition arithmetic; not real issuer payout validation'} | ConvertTo-Json | Set-Content (Join-Path $RunPath 'independent-verification.json') -Encoding utf8
"PASS: $verified runs; $evaluations independent candidate calculations; monthly state and Java Random mapping."
