param([Parameter(Mandatory=$true)][string]$RunPath)
$ErrorActionPreference='Stop'
if ((Get-Content (Join-Path $RunPath 'status.txt') -Raw) -ne 'SUCCESS') {throw 'Successful run required'}
$summary=@(Get-Content (Join-Path $RunPath 'summary.json') -Raw | ConvertFrom-Json)
$monthly=@(Get-Content (Join-Path $RunPath 'monthly.json') -Raw | ConvertFrom-Json)
$inputRows=@(Get-Content (Join-Path $RunPath 'transactions.json') -Raw | ConvertFrom-Json)
$names=@{FIXED_FIRST='처음만 사용';FIXED_TOKTOK='톡톡만 사용';CATEGORY='업종별 고정 사용';YAMU='YAMU';RANDOM='균등 랜덤'}
$yamu=[decimal]($summary | Where-Object strategy -eq YAMU).totalKrw
$random=@($summary | Where-Object strategy -eq RANDOM | Sort-Object totalKrw)
$mean=($random | Measure-Object totalKrw -Average).Average
$sd=[Math]::Sqrt((($random | ForEach-Object {[Math]::Pow($_.totalKrw-$mean,2)}) | Measure-Object -Average).Average)
$spending=($inputRows | Measure-Object amount -Sum).Sum
$stats=[ordered]@{count=100;mean=$mean;min=$random[0].totalKrw;p05=$random[4].totalKrw;median=($random[49].totalKrw+$random[50].totalKrw)/2;p95=$random[94].totalKrw;max=$random[99].totalKrw;populationStdDev=$sd;greaterThanYamu=@($random | Where-Object totalKrw -gt $yamu).Count;equalToYamu=@($random | Where-Object totalKrw -eq $yamu).Count;lessThanYamu=@($random | Where-Object totalKrw -lt $yamu).Count}
$stats | ConvertTo-Json | Set-Content (Join-Path $RunPath 'random-statistics.json') -Encoding utf8
$summary | Select-Object strategy,seed,totalKrw,@{n='yamuMinusStrategyKrw';e={$yamu-$_.totalKrw}} | Export-Csv (Join-Path $RunPath 'summary.csv') -NoTypeInformation -Encoding utf8
$monthly | ForEach-Object {
 [pscustomobject]@{strategy=$_.strategy;seed=$_.seed;month=$_.month;spending=$_.spending;benefit=$_.totalKrw;firstPrevious=$_.previousPerformance[0];toktokPrevious=$_.previousPerformance[1];firstRecognized=$_.recognizedPerformance[0];toktokRecognized=$_.recognizedPerformance[1];firstNextMinimumEligible=($_.recognizedPerformance[0] -ge 300000);toktokNextMinimumEligible=($_.recognizedPerformance[1] -ge 300000);firstNextCap=$(if($_.recognizedPerformance[0] -ge 1000000){20000}elseif($_.recognizedPerformance[0] -ge 500000){10000}elseif($_.recognizedPerformance[0] -ge 300000){5000}else{0})}
} | Export-Csv (Join-Path $RunPath 'monthly-performance.csv') -NoTypeInformation -Encoding utf8
$spendRows=@($inputRows | Group-Object {([DateTimeOffset]$_.at).ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM')} | ForEach-Object {
 $month=$_.Name
 foreach($group in ($_.Group | Group-Object purpose)) {[pscustomobject]@{month=$month;purpose=$group.Name;count=$group.Count;spending=($group.Group | Measure-Object amount -Sum).Sum}}
})
$spendRows | Export-Csv (Join-Path $RunPath 'monthly-spending.csv') -NoTypeInformation -Encoding utf8
$lines=[Collections.Generic.List[string]]::new()
$lines.Add('# EXP-006 집계 결과')
$lines.Add('')
$lines.Add("총 $($inputRows.Count)건, 소비액 $('{0:N0}' -f $spending)원. 차액은 YAMU − 해당 방법이며 양수일 때 YAMU가 더 높다.")
$lines.Add('')
$lines.Add('| 선택 방법 | 6개월 혜택(원) | YAMU 차액(원) | 소비액 대비 혜택 |')
$lines.Add('|---|---:|---:|---:|')
foreach($row in ($summary | Where-Object strategy -ne RANDOM)) {$lines.Add("| $($names[$row.strategy]) | $('{0:N0}' -f $row.totalKrw) | $('{0:N0}' -f ($yamu-$row.totalKrw)) | $('{0:P2}' -f ($row.totalKrw/$spending)) |")}
$lines.Add("| 균등 랜덤 평균 | $('{0:N2}' -f $mean) | $('{0:N2}' -f ($yamu-$mean)) | $('{0:P2}' -f ($mean/$spending)) |")
$lines.Add('')
$lines.Add("랜덤 100경로: 최솟값 $($stats.min), P05 $($stats.p05), 중앙값 $($stats.median), P95 $($stats.p95), 최댓값 $($stats.max)원. 모집단 표준편차 $('{0:N2}' -f $sd)원. YAMU보다 높은 경로 $($stats.greaterThanYamu), 동률 $($stats.equalToYamu), 낮은 경로 $($stats.lessThanYamu)개. P05·P95는 오름차순 5번째·95번째 값이다.")
$lines.Add('')
$lines.Add('## 월별 소비와 혜택')
$lines.Add('')
$lines.Add('| 월 | 거래 수 | 소비액 | 처음만 | 톡톡만 | 업종별 고정 | YAMU | 랜덤 평균 |')
$lines.Add('|---|---:|---:|---:|---:|---:|---:|---:|')
foreach($g in ($monthly | Group-Object month)) {
 $m=$g.Name; $r=$g.Group; $values=@('FIXED_FIRST','FIXED_TOKTOK','CATEGORY','YAMU' | ForEach-Object {($r | Where-Object strategy -eq $_).totalKrw})
 $randomMean=($r | Where-Object strategy -eq RANDOM | Measure-Object totalKrw -Average).Average
 $count=($spendRows | Where-Object month -eq $m | Measure-Object count -Sum).Sum
 $lines.Add("| $m | $count | $($r[0].spending) | $($values -join ' | ') | $('{0:F2}' -f $randomMean) |")
}
$lines.Add('')
$lines.Add('## 카드별 당월 인정 실적과 다음 달 자격')
$lines.Add('')
$lines.Add('금액 순서는 처음 / 톡톡이다. 자격은 최소 실적 30만 원 기준이다. 처음의 월 한도는 30만·50만·100만 원 구간에서 각각 5천·1만·2만 원이다. 4월 값은 5월 자격의 계산일 뿐 5월 거래는 실행하지 않았다. 첫 달 전월 실적은 각각 40만 원이며 이후 전월 실적 원자료는 monthly-performance.csv에 있다.')
$lines.Add('')
$lines.Add('| 방법 | 월 | 인정 실적(원) | 다음 달 최소 실적 충족(처음 / 톡톡) |')
$lines.Add('|---|---|---:|---|')
foreach($r in ($monthly | Where-Object strategy -ne RANDOM)) {
 $a=if($r.recognizedPerformance[0] -ge 300000){'충족'}else{'미달'}; $b=if($r.recognizedPerformance[1] -ge 300000){'충족'}else{'미달'}
 $lines.Add("| $($names[$r.strategy]) | $($r.month) | $($r.recognizedPerformance -join ' / ') | $a / $b |")
}
$lines.Add('')
$lines.Add('## 월별 생활비 구성')
$lines.Add('')
$purposes=@{LUNCH='점심';GROCERIES='장보기';CAFE='카페';CONVENIENCE='편의점';COMMUTE='출퇴근';TAXI='택시';CLOTHING='의류';HOUSEHOLD='생활용품';SUBSCRIPTION='구독'}
$lines.Add('| 월 | 용도 | 건수 | 금액(원) |')
$lines.Add('|---|---|---:|---:|')
foreach($r in $spendRows){$lines.Add("| $($r.month) | $($purposes[$r.purpose]) | $($r.count) | $($r.spending) |")}
$lines | Set-Content (Join-Path $RunPath 'results.md') -Encoding utf8
$stats | ConvertTo-Json
