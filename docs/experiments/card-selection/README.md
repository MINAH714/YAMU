# 카드 선택 비교 실험 EXP-001~006

[종합 결론](conclusions-and-user-validation-plan.md) · [기록 점검표](record-audit.md) · [개인정보 공개 범위](privacy-review.md)

동일한 보유 카드와 거래에서 카드 선택 방법별 총혜택을 비교했다. 실사용자 후속 실험은 진행하지 않는다.

**EXP-006 발표 결론:** 선정한 두 카드와 6개월 합성 소비에서 YAMU는 **톡톡만 사용 대비 혜택 11,300원·약 19.3% 증가**. 19.3%는 톡톡의 혜택 58,500원 대비 증가율이며 소비액 환급률이 아니다. [근거·전체 비교·한계](exp-006-six-months/README.md)를 함께 제시한다.

| 실험 | 조건 | 결과·기록 |
|---|---|---|
| EXP-001 | 가상 거래 24건, 카드 2장 | [보고서](exp-001-two-cards/README.md) |
| EXP-002 | 같은 거래, 카드 3장 | [보고서](exp-002-three-cards/README.md) |
| EXP-003 | 거래 금액 10·15·25·50·75% × 카드 2·3장 | [보고서](exp-003-consumption-scale/README.md) |
| EXP-004 | 기존 12조건에 혜택표 기반 수동 선택 추가 | [보고서](exp-004-manual-table/README.md) |
| EXP-005 | 실제 내역 58건, A/B 및 정책 12조건 | [개인정보를 제외한 결과](exp-005-deep-dream/execution/README.md) |
| EXP-006 | EXP-001의 두 카드, 생활비 6개월 합성 거래 521건, 전월 실적 이월 | [결과·재현 방법](exp-006-six-months/README.md) · [월별·누적 차액과 그래프](exp-006-six-months/differences.md) · [사전 계획](exp-006-six-months/plan.md) · [실행 이력](exp-006-six-months/execution-history.md) |

EXP-001~004의 입력·거래별 결과·한도 변화·코드 보존본은 해당 보고서와 executions/ 아래에 있다. 압축 파일은 Verify-Archives.ps1로 검증한다. 보존된 실행의 코드·정책 버전과 최신 develop은 다를 수 있으므로 최신 코드의 재실행을 원본 결과로 덮어쓰지 않는다.

EXP-005는 개인 원자료와 거래별 결과, 과거 원문 보고서, 실제 재생 코드·설정을 이 MR에서 제외했다. 집계 CSV의 수치는 원본과 같으며 공개 검증 코드는 합성 데이터만 사용한다. 공개 코드만으로 비공개 실제 사례를 재현할 수 있다고 주장하지 않는다.

EXP-006은 사용자 요청에 따라 이전 EXP-006을 생활비 거래로 교체했다. 소비액 5,609,000원에서 YAMU 69,800원, 처음만 63,850원, 톡톡만 58,500원, 업종별 고정 67,850원이었다. 랜덤 100경로 중 9개는 YAMU보다 높았다. 실적 보장용 결제나 강제 배정은 없으며 YAMU에서는 2개월차부터 톡톡 실적이 미달했다. 11월 정책 유지와 전월 실적 이월을 적용했으며, 기간 증가의 순수 효과나 일반 사용자 평균으로 해석하지 않는다. EXP-001~005와 그 종합 문서는 그대로 보존한다.

## 현재 코드에서 실험 실행하기 (S15P31E103-538)

일반 `test`는 회귀 테스트만 실행한다. `experiment` 태그를 붙인 비교 실험 5개 클래스는
일반 CI·MR 검증·배포 전 검사에서 제외하며, 일반 테스트의 실패·스킵 차단 기준은 유지한다.
추천 계산·카탈로그·결제·DB 회귀 테스트는 계속 일반 `test`에서 실행한다.

분리 대상은 아래 클래스뿐이다. 이름이 비슷한 추천·카탈로그 회귀 테스트를 패턴으로
제외하지 않고, 각 실험 클래스에 붙은 태그로 구분한다.

| 클래스 | 실행 태스크 |
|---|---|
| `MonthlyCardSelectionSimulationTest` | `experimentTest` |
| `CardSelectionExperimentTest` | `experimentTest` |
| `CardSelectionConsumptionScaleTest` | `experimentTest` |
| `CardSelectionManualTableTest` | `experimentTest` |
| `CardSelectionSixMonthTest` 사전 검증 | `experimentTest` |
| `CardSelectionSixMonthTest` 승인용 본실험 | `sixMonthExperimentTest` |

`be` 폴더에서 Java 17과 Docker를 준비한 뒤 실행한다.

```powershell
# EXP-001~004 비교 실험 및 EXP-006 사전 검증. 승인용 본실험은 포함하지 않는다.
.\gradlew.bat experimentTest

# 실험 한 개만 선택하는 예. 기존 문서의 test --tests는 experimentTest --tests로 바꾼다.
.\gradlew.bat experimentTest --tests '*CardSelectionManualTableTest'

# EXP-006 본실험은 별도 승인값이 필요하다. 없으면 스킵 대신 즉시 실패한다.
$env:YAMU_EXP006_EXECUTE = 'APPROVED_6_MONTHS_V1'
try {
    .\gradlew.bat sixMonthExperimentTest
} finally {
    Remove-Item Env:YAMU_EXP006_EXECUTE
}
```

결과 XML은 각각 `be/build/test-results/experimentTest`와
`be/build/test-results/sixMonthExperimentTest`에 생긴다. 저장소 루트에서 아래 검사기로
실패·Docker 부재로 인한 스킵·빈 결과를 확인한다.

```powershell
python infra/ci/check-test-results.py be/build/test-results/experimentTest
python infra/ci/check-test-results.py be/build/test-results/sixMonthExperimentTest
```

실험 태스크는 이전 결과를 up-to-date나 빌드 캐시로 재사용하지 않는다. 입력·계산·원본 보존
규칙은 기존과 같다. `executions/` 등 과거 실행 보고서의 명령·소스 스냅샷은 당시 기록으로
보존하며, 현재 코드 재실행에는 위 명령을 사용한다.
