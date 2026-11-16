# EXP-004 혜택표와 잔여 한도를 확인하는 수동 선택

**12개 조건 모두 수동 선택과 YAMU가 동률이었다.** 288건의 카드 선택과 한도 차감 이력·최종 실적·사용액도 같았다. 후보별 예상·실제 비교 720건에서 차이 0건이다. 같은 선택을 차이 없이 공개하며 인간의 시간·수고·오류율을 검증한 것으로 해석하지 않는다.

[전체 색인](../README.md) · [승인 범위·고정 규칙·혜택표](plan-and-rules.md) · [고정 JSON](frozen-manual-table.json) · [전체 수치 CSV](comparison.csv) · [보존 검증](preservation-verification.json)

실행 ID: `exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8`. [실행 상태](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/status.txt), [실행·코드 버전](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/execution-validation.json), [수동/YAMU 상태 동등성](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/manual-yamu-state-equivalence.json). 첫 시도 성공, 조건 변경이나 재실행 없음. 정책 미확정·정보 부족 때문에 거래를 변경하지 않았다.

## 전체 전략 비교

단위 원. 처음·톡톡·LOCA는 각 카드 고정 사용. 업종별 고정은 한도 소진 후에도 지정 카드를 유지하는 기존 CATEGORY다. 수동은 새 MANUAL_TABLE이며 최대 예상 혜택을 선택한다. 모든 차액은 YAMU 대비로 각 조건 summary.csv에 있다.

| 소비 | 장수 | 처음 | 톡톡 | LOCA | 랜덤 평균 | 업종별 고정 | 수동 | YAMU | YAMU−업종 고정 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10% | 2 | 1545 | 1900 | — | 1677.1 | 3255 | 3255 | 3255 | 0 |
| 10% | 3 | 1545 | 1900 | 7020 | 3442.45 | 7600 | 7980 | 7980 | 380 |
| 15% | 2 | 2313 | 2850 | — | 2513.64 | 4879 | 4879 | 4879 | 0 |
| 15% | 3 | 2313 | 2850 | 10530 | 5162.16 | 11398 | 11968 | 11968 | 570 |
| 25% | 2 | 3858 | 4750 | — | 4190.74 | 8134 | 8134 | 8134 | 0 |
| 25% | 3 | 3858 | 4750 | 13000 | 8596.61 | 18998 | 17285 | 17285 | -1713 |
| 50% | 2 | 5000 | 9500 | — | 8107.25 | 14500 | 14500 | 14500 | 0 |
| 50% | 3 | 5000 | 9500 | 13000 | 15693.0 | 26825 | 24750 | 24750 | -2075 |
| 75% | 2 | 5000 | 10000 | — | 11025.93 | 15000 | 15000 | 15000 | 0 |
| 75% | 3 | 5000 | 10000 | 13000 | 19221.54 | 28000 | 28000 | 28000 | 0 |
| 100% | 2 | 5000 | 10000 | — | 12736.0 | 15000 | 15000 | 15000 | 0 |
| 100% | 3 | 5000 | 10000 | 13000 | 21350.0 | 28000 | 28000 | 28000 | 0 |

## 차이와 한도 사용 순서

수동과 YAMU 사이에는 총액뿐 아니라 거래 선택·혜택·한도 사용 순서의 차이도 없었다. 따라서 후속 거래의 잔여 한도에 미친 차이도 0이다. 실제 한도 이력까지 별도 대조했으며 각 거래의 두 상세 기록은 manual-vs-yamu.csv에 있다.

예를 들어 100%·3장에서 LOCA는 첫 음식점에 7,200원, 이어 커피에 3,600원을 써 공유 월 잔여가 2,200원이 된다. 5번 음식점 거래의 원시 예상액은 9,000원이지만 잔여 적용 후 2,200원이다. 두 방식 모두 다른 카드보다 큰 2,200원을 선택한다. 이후 LOCA는 정상 소진 0원으로 평가하고 다른 카드를 선택한다. 부분 잔여를 무조건 유지하는 규칙은 아니다.

3장·10%/15%에서 두 방식은 커피의 LOCA 60%를 톡톡 50%보다 높게 평가해 업종별 고정보다 각각 380원/570원 높았다. 반면 25%/50%에서는 커피에 LOCA 공유 월 한도를 먼저 사용해 뒤 음식점의 혜택 기회가 줄어 업종별 고정보다 각각 1,713원/2,075원 낮았다. 기존 EXP-003의 거래별 분석과 같은 결과이며 [원래 분석](../exp-003-consumption-scale/README.md)을 보존했다. 75%/100%에서는 합계가 같았다. 카드 추가 효과와 수동/YAMU 선택 방식 차이는 구분해야 한다. 이번 새 전략 추가 자체가 카드 구성을 바꾼 것은 아니다.

## 검증과 원자료

- 본 실행 1,266회, 후보 카드 평가 76,032건, 계산 불가 0건. 기존 1,254회는 원본 경제 기록과 일치했다. 수동 12회가 새로 추가됐다. JUnit는 내부 전체 실험을 수행한 테스트 1개, 실패·오류·skip 0개다.
- 경계 3,168개 상태에서 8,064개 카드 평가를 검사했다. 초기·모든 한도 소진·개별 한도 잔여 1/0에서 표와 실제 계산을 대조했다. 일 한도 다음 날짜 초기화, 대표 음식점·커피·실적 제외, 1원 대 1,000원·동률·절사·정보 누락 중단 검사를 통과했다.
- 수동 후보 예상·실제 720건 모두 차이 0원. 이는 선택한 288건 외 비선택 후보도 포함한다. 차이를 0으로 대체하지 않았으며 코드가 선택 전에 독립 표 계산을 수행한다.
- 랜덤은 시드 0~99를 유지했다. 같은 장수에서 시드별 선택 배열도 모든 배율에 걸쳐 동일하다. 2장/3장 사이 매핑 차이는 원래 실험대로 유지된다.
- 새 소스 3개와 12조건 원자료 60개를 압축·해시 검증했다. 전체 보존 아카이브 199개 항목이 검증됐다. 기존 build 원본도 보존 검증했다.

| 조건·원실험 | 결과 보고서 | 수동 예상/실제 | 수동/YAMU 거래 비교 | 전체 원자료 |
|---|---|---|---|---|
| 10%·2장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-2/raw-records.zip) |
| 10%·3장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-10-cards-3/raw-records.zip) |
| 15%·2장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-2/raw-records.zip) |
| 15%·3장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-15-cards-3/raw-records.zip) |
| 25%·2장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-2/raw-records.zip) |
| 25%·3장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-25-cards-3/raw-records.zip) |
| 50%·2장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-2/raw-records.zip) |
| 50%·3장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-50-cards-3/raw-records.zip) |
| 75%·2장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-2/raw-records.zip) |
| 75%·3장 / EXP-003 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-75-cards-3/raw-records.zip) |
| 100%·2장 / EXP-001 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-2/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-2/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-2/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-2/raw-records.zip) |
| 100%·3장 / EXP-002 | [보고서](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-3/report.md) | [후보별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-3/manual-estimates.csv) | [거래별 CSV](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-3/manual-vs-yamu.csv) | [ZIP](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/scale-100-cards-3/raw-records.zip) |

각 조건 폴더의 summary.csv는 105/106개 전체 실행의 총액·차액이다. random-statistics.json과 random-distribution.csv에 평균·표준편차·최소/최대·백분위·전체 분포를 보존했다. raw-records.zip에는 runs.json, transactions.csv, limits.csv, prepared-policies.json, coverage.json이 있다. manual-estimates.csv는 거래별 후보 예상·실제·오차·판단 잔여 한도·선택 사유를, raw limits.csv는 실제 전후 한도와 차감량을 담는다. limit-utilization.csv에는 일/월 한도 사용률과 최초 소진 시점이 있다. effective-input.json과 manifest.json은 재현 입력과 미커밋 포함 코드·정책 버전이다. archive-reproduction.json, table-validation.json, validation.json, manual-checks.json, manual-audit.json은 검사 증거다.

## 재현과 기록 한계

`be`에서 `./gradlew.bat test --tests com.yamu.be.recommendation.CardSelectionManualTableTest --rerun-tasks`를 실행한다. Docker가 필요하다. 매번 새 exp004-UTC-UUID 폴더를 만들며 기존 실험에 쓰지 않는다. 동일 조건 재실행은 EXP-004의 새 실행 ID, 규칙·입력 변경은 별도 실험으로 구분한다.

[실행 소스](../executions/exp004-2026-11-12T16-23-10.237062700Z-2738fc34-6f5d-4064-9c0b-fc362dd1f5e8/execution-source.zip)와 체크섬을 보존했다. 별도 checkout 복원은 [색인의 복원 절차](../README.md#코드-상태-복원과-보존-범위)에 따라 EXP-001 overlay, EXP-002/003 소스·입력, 이번 소스를 순서대로 적용한다. 현재 작업 디렉터리에 복원본을 덮어쓰지 않는다. 정책 시드·프로젝트 의존성은 원본 보존본을 따른다.

선정한 카드와 합성 소비 조건에서 정확한 혜택표·사용량을 아는 수동 규칙과 비교한 결과다. 실제 사람의 계산 시간·수고·오류율, 일반 사용자 평균, 미래를 고려한 월간 최적은 측정하지 않았다. 모든 전략의 정상 결제 성공을 가정한 순차 실험이며 동시 결제·재시도·외부 실패·실제 지급 검증은 범위 밖이다.

첫 실험 당시 계획 전문·실패 원문·완전한 환경 digest 등 기존 기록 미확인 항목은 [기록 점검표](../record-audit.md)에 그대로 남겼다. 별도 머신 clean 복원은 미실시다. 이번 첫 시도에는 실행 오류가 없었다. Git 커밋·push 및 Jira·외부 문서 수정은 하지 않았다.
