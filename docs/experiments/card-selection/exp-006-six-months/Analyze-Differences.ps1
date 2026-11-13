param([Parameter(Mandatory=$true)][string]$RunPath)
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
if((Get-Content (Join-Path $RunPath 'status.txt') -Raw) -ne 'SUCCESS'){throw 'Successful run required'}
$monthly=@(Get-Content (Join-Path $RunPath 'monthly.json') -Raw | ConvertFrom-Json)
$summary=@(Get-Content (Join-Path $RunPath 'summary.json') -Raw | ConvertFrom-Json)
$months=@($monthly.month | Sort-Object -Unique)
if($months.Count -ne 6 -or $monthly.Count -ne 624){throw 'Unexpected run coverage'}
$output=Join-Path $RunPath 'difference-analysis'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$labels=@{FIXED_FIRST='처음만 사용';FIXED_TOKTOK='톡톡만 사용';CATEGORY='업종별 고정';RANDOM='랜덤 평균'}
$strategies=@('FIXED_FIRST','FIXED_TOKTOK','CATEGORY','RANDOM')
$yamu=@($monthly | Where-Object strategy -eq YAMU | Sort-Object month)
if($yamu.Count -ne 6 -or ($yamu.month -join ',') -ne ($months -join ',')){throw 'Missing YAMU month'}
[decimal]$yamuTotal=0;foreach($row in $yamu){$yamuTotal+=$row.totalKrw}
if($yamuTotal -ne ($summary | Where-Object strategy -eq YAMU).totalKrw){throw 'YAMU summary mismatch'}
$details=[Collections.Generic.List[object]]::new()
# Preserve each seed's continuous six-month path before computing distributions.
foreach($strategy in $strategies){
 $seeds=if($strategy -eq 'RANDOM'){0..99}else{@(-1)}
 foreach($seed in $seeds){
  $path=@($monthly | Where-Object {$_.strategy -eq $strategy -and $_.seed -eq $seed} | Sort-Object month)
  if($path.Count -ne 6 -or ($path.month -join ',') -ne ($months -join ',')){throw 'Missing path month'}
  [decimal]$cumulativeYamu=0;[decimal]$cumulativeOther=0;[decimal]$cumulativeDifference=0
  for($i=0;$i -lt 6;$i++){
   [decimal]$difference=$yamu[$i].totalKrw-$path[$i].totalKrw
   $cumulativeYamu+=$yamu[$i].totalKrw;$cumulativeOther+=$path[$i].totalKrw;$cumulativeDifference+=$difference
   if($cumulativeDifference -ne $cumulativeYamu-$cumulativeOther){throw 'Cumulative identity failed'}
   $details.Add([pscustomobject]@{strategy=$strategy;seed=$seed;month=$months[$i];yamuBenefit=[decimal]$yamu[$i].totalKrw;otherBenefit=[decimal]$path[$i].totalKrw;monthlyDifference=$difference;cumulativeYamu=$cumulativeYamu;cumulativeOther=$cumulativeOther;cumulativeDifference=$cumulativeDifference})
  }
  $original=@($summary | Where-Object {$_.strategy -eq $strategy -and $_.seed -eq $seed})
  if($original.Count -ne 1 -or $cumulativeOther -ne $original[0].totalKrw){throw 'Original summary mismatch'}
 }
}
$details | Export-Csv (Join-Path $output 'path-differences.csv') -NoTypeInformation -Encoding utf8
$rows=@(foreach($strategy in $strategies){foreach($month in $months){
 $group=@($details | Where-Object {$_.strategy -eq $strategy -and $_.month -eq $month})
 $row=[ordered]@{strategy=$strategy;month=$month;paths=$group.Count}
 foreach($field in @('yamuBenefit','otherBenefit','monthlyDifference','cumulativeYamu','cumulativeOther','cumulativeDifference')){
  [decimal]$sum=0;foreach($item in $group){$sum+=[decimal]$item.$field};$row[$field]=$sum/$group.Count
 }
 if($row.cumulativeDifference -ne $row.cumulativeYamu-$row.cumulativeOther){throw 'Mean identity failed'}
 [pscustomobject]$row
}})
foreach($strategy in $strategies){[decimal]$sum=0;foreach($row in ($rows | Where-Object strategy -eq $strategy)){$sum+=$row.monthlyDifference;if($sum -ne $row.cumulativeDifference){throw 'Sum of monthly means mismatch'}}}
$rows | Export-Csv (Join-Path $output 'monthly-and-cumulative.csv') -NoTypeInformation -Encoding utf8
$distribution=@(foreach($month in $months){
 $group=@($details | Where-Object {$_.strategy -eq 'RANDOM' -and $_.month -eq $month} | Sort-Object cumulativeDifference)
 [decimal]$sum=0;foreach($row in $group){$sum+=$row.cumulativeDifference}
 [pscustomobject]@{month=$month;paths=$group.Count;min=$group[0].cumulativeDifference;p05=$group[4].cumulativeDifference;median=($group[49].cumulativeDifference+$group[50].cumulativeDifference)/2;mean=$sum/100;p95=$group[94].cumulativeDifference;max=$group[99].cumulativeDifference;negative=@($group | Where-Object cumulativeDifference -lt 0).Count;zero=@($group | Where-Object cumulativeDifference -eq 0).Count;positive=@($group | Where-Object cumulativeDifference -gt 0).Count}
})
$distribution | Export-Csv (Join-Path $output 'random-cumulative-distribution.csv') -NoTypeInformation -Encoding utf8

function Format-Amount($value){return ([decimal]$value).ToString('#,##0.##',$culture)}
$table=[Collections.Generic.List[string]]::new()
$table.Add('# 최신 EXP-006 월별·누적 집계표')
$table.Add('')
$table.Add('모든 금액은 원. 차액은 YAMU − 비교군이다. 랜덤은 시드 0~99의 연속 경로 100개 평균이며 실제 사용자 평균이 아니다.')
foreach($field in @('otherBenefit','monthlyDifference','cumulativeDifference')){
 $title=switch($field){'otherBenefit'{'월별 혜택'}'monthlyDifference'{'월별 차액'}'cumulativeDifference'{'누적 차액'}}
 $table.Add('');$table.Add("## $title");$table.Add('')
 if($field -eq 'otherBenefit'){$table.Add('| 월 | YAMU | 처음만 사용 | 톡톡만 사용 | 업종별 고정 | 랜덤 평균 |');$table.Add('|---|---:|---:|---:|---:|---:|')}
 else{$table.Add('| 월 | 처음만 사용 대비 | 톡톡만 사용 대비 | 업종별 고정 대비 | 랜덤 평균 대비 |');$table.Add('|---|---:|---:|---:|---:|')}
 foreach($month in $months){
  $cells=[Collections.Generic.List[string]]::new();$cells.Add($month)
  if($field -eq 'otherBenefit'){$cells.Add((Format-Amount ($yamu | Where-Object month -eq $month).totalKrw))}
  foreach($strategy in $strategies){$cells.Add((Format-Amount ($rows | Where-Object {$_.strategy -eq $strategy -and $_.month -eq $month}).$field))}
  $table.Add('| '+($cells -join ' | ')+' |')
 }
}
$table.Add('');$table.Add('## 랜덤 경로별 누적 차액의 분포');$table.Add('')
$table.Add('같은 시드의 첫 달~해당 월 차액을 합한 뒤 100개를 정렬했다. 월별 백분위 값을 합산하지 않았다. P05·P95는 각각 정렬한 값의 5번째·95번째다. 음수 경로 수는 해당 월까지 YAMU보다 랜덤 누적 혜택이 높은 경로 수다.')
$table.Add('');$table.Add('| 월 | 최소 | P05 | 중앙값 | 평균 | P95 | 최대 | 음수 경로 수 | 동률 경로 수 |');$table.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach($row in $distribution){$cells=@($row.month);foreach($field in @('min','p05','median','mean','p95','max','negative','zero')){$cells+=(Format-Amount $row.$field)};$table.Add('| '+($cells -join ' | ')+' |')}
$table | Set-Content (Join-Path $output 'tables.md') -Encoding utf8

# Static PNG charts via Windows GDI+, with fixed axes including negative values.
Add-Type -AssemblyName System.Drawing
function Draw-Chart([string]$field,[string]$title,[string]$filename,[int]$max){
 $bitmap=[Drawing.Bitmap]::new(1440,840);$g=[Drawing.Graphics]::FromImage($bitmap)
 $g.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
 $g.TextRenderingHint=[Drawing.Text.TextRenderingHint]::AntiAliasGridFit
 $g.Clear([Drawing.Color]::White)
 $font=[Drawing.Font]::new('Malgun Gothic',16);$small=[Drawing.Font]::new('Malgun Gothic',13);$heading=[Drawing.Font]::new('Malgun Gothic',25,[Drawing.FontStyle]::Bold)
 $ink=[Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#172b4d'))
 $gray=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#e1e5eb'),1)
 $zero=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#68778b'),2)
 $colors=@('#1769c2','#dc6a15','#00856a','#9751a5')
 $left=125;$right=1260;$top=200;$bottom=665;$min=-6000
 function X([int]$i){return [single]($left+($right-$left)*$i/5)}
 function Y([decimal]$v){return [single]($bottom-($v-$min)*($bottom-$top)/($max-$min))}
 try{
  $g.DrawString($title,$heading,$ink,50,28)
  $g.DrawString('차액 = YAMU 혜택 − 비교군 혜택 (원)   |   양수: YAMU가 더 받음 · 음수: 비교군이 더 받음',$font,$ink,50,83)
  for($s=0;$s -lt 4;$s++){
   $pen=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($colors[$s]),4)
   $lx=60+$s*325;$g.DrawLine($pen,$lx,150,($lx+34),150);$g.DrawString($labels[$strategies[$s]],$font,$ink,($lx+45),135);$pen.Dispose()
  }
  for($tick=$min;$tick -le $max;$tick+=2000){
   $yy=Y $tick;$g.DrawLine($(if($tick -eq 0){$zero}else{$gray}),$left,$yy,$right,$yy)
   $text=$tick.ToString('N0',$culture);$size=$g.MeasureString($text,$small)
   $g.DrawString($text,$small,$ink,([single]($left-15-$size.Width)),([single]($yy-12)))
  }
  for($i=0;$i -lt 6;$i++){$xx=X $i;$g.DrawString($months[$i],$small,$ink,([single]($xx-37)),685)}
  # Dashes keep overlapping zero/change lines distinguishable; every point has a marker.
  for($s=0;$s -lt 4;$s++){
   $series=@($rows | Where-Object strategy -eq $strategies[$s])
   $pen=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml($colors[$s]),3)
   if($s -eq 1){$pen.DashStyle=[Drawing.Drawing2D.DashStyle]::Dash}
   if($s -eq 2){$pen.DashStyle=[Drawing.Drawing2D.DashStyle]::Dot}
   $brush=[Drawing.SolidBrush]::new($pen.Color)
   for($i=0;$i -lt 6;$i++){
    $xx=X $i;$yy=Y $series[$i].$field
    if($i -gt 0){$g.DrawLine($pen,(X ($i-1)),(Y $series[$i-1].$field),$xx,$yy)}
    $g.FillEllipse($brush,([single]($xx-5)),([single]($yy-5)),10,10)
    if($i -eq 5){$g.DrawString($series[$i].$field.ToString('#,##0.##',$culture),$small,$brush,([single]($xx+14)),([single]($yy-13)))}
   }
   $brush.Dispose();$pen.Dispose()
  }
  $g.DrawString('EXP-006 · 합성 거래 521건 / 6개월 · 랜덤은 고정 시드 100경로의 평균 (실제 사용자 수 아님)',$small,$ink,50,744)
  $g.DrawString('입력·정책·전략 변경 없음. 최신 실행의 기존 결과만 집계함.',$small,$ink,50,778)
  $bitmap.Save((Join-Path $output $filename),[Drawing.Imaging.ImageFormat]::Png)
 }finally{$gray.Dispose();$zero.Dispose();$ink.Dispose();$font.Dispose();$small.Dispose();$heading.Dispose();$g.Dispose();$bitmap.Dispose()}
}
Draw-Chart 'monthlyDifference' 'YAMU는 매달 얼마를 더 받았나?' 'monthly-difference.png' 12000
Draw-Chart 'cumulativeDifference' 'YAMU와의 차액은 6개월 동안 어떻게 쌓였나?' 'cumulative-difference.png' 14000
$validation=[ordered]@{status='PASS';sourceRun=(Split-Path $RunPath -Leaf);analysisScriptSha256=(Get-FileHash $PSCommandPath).Hash;sourceMonthlySha256=(Get-FileHash (Join-Path $RunPath 'monthly.json')).Hash;sourceSummarySha256=(Get-FileHash (Join-Path $RunPath 'summary.json')).Hash;paths=103;pathMonthChecks=$details.Count;aggregateMonthChecks=$rows.Count;originalSummaryMatches=$true;yamuSummaryMatches=$true;cumulativeIdentityMatches=$true;meanMonthlySumMatches=$true;randomSeeds='0..99; continuous paths';quantiles='Nearest rank: 5th and 95th of 100 cumulative path differences; never sum monthly quantiles';newExperimentRun=$false}
$validation | ConvertTo-Json | Set-Content (Join-Path $output 'validation.json') -Encoding utf8
$rows | Where-Object month -eq $months[-1] | Select-Object strategy,cumulativeDifference | Format-Table
