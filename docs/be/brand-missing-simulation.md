# 브랜드 미입력 혜택 계산 인계 — S15P31E103-296

2026-11-05 승인 설계를 구현했다. 브랜드 조건이 있다는 이유로 혜택 전체를 일괄 제외하지 않는다. 기존 조건 판정이 UNKNOWN이고, 남은 미확인 원인이 브랜드뿐일 때 명시적 시뮬레이션 옵션으로 제외한다. 실제 결제 생성·한도/실적 갱신·잔고예보·앱 문구는 이 작업에 포함하지 않는다.

## 호출 계약

```java
// 실제 POS/승인: 기존 호출 유지. 옵션이나 모의 가정을 자동 적용하지 않는다.
Calculation actual = calculator.calculate(transaction, policy);

// 시뮬레이션: 브랜드 제외 OFF + 누락 정보 설명
Calculation strict = calculator.calculate(transaction, policy, CalculationOptions.DEFAULT);

// 시뮬레이션 담당자가 명시적으로 선택한 경우에만 사용
Calculation scoped = calculator.calculate(transaction, policy, new CalculationOptions(true));
```

`CalculationOptions`의 필드는 `excludeBrandDependentBenefits`다. `false`가 기본이다. 기존 2인자 호출의 금액·완료 상태·문제 코드는 유지한다. 3인자 OFF 호출도 기존 계산 결과를 유지하고 누락 정보 설명을 추가한다. `merchantId`나 `facts`를 변경하지 않는다.

시뮬레이션 담당자는 서버에서 검증한 `RecommendationInput.Transaction`, 카드별 `BenefitPolicy`를 공급한다. 카드 식별은 기존 `CardEvaluation.userCardId` 등의 바깥 카드 결과를 재사용한다. 새로운 결제 ID를 만들거나 `RecommendationService.prepare()`를 가상 구매용으로 호출하지 않는다. 해당 서비스/Selector의 실제 결제 경로에는 옵션을 연결하지 않았다.

## 결과 필드

| 필드 | 의미 |
|---|---|
| `complete` | 선택한 계산 범위에서 총 순혜택을 계산했는지 |
| `netBenefit` | 순혜택. 계산 불가면 `null`; 확인된 0과 구분 |
| `problems` | 기존 계산 차단 문제(`benefitId`, `code`). 완료 결과에서는 빈 목록 |
| `missingInformation` | `benefitId`, `code`, `field`. 판정 중 결과에 영향을 주는 누락 입력. 이 목록 자체는 실패 여부가 아님 |
| `excludedBenefits` | 실제 계산 범위에서 제외한 `benefitId`, `reasonCode=BRAND_MISSING`. 빈 목록이면 브랜드 제외가 실제 적용되지 않음 |
| `simulationRules` | 기존 `SimulationRule` 재사용. 전달받은 거래 가정, 사용한 정책 근거, 실제 브랜드 제외 근거 |

추가 목록은 JSON에서 비어 있으면 생략한다. 기존 5/6인자 `Calculation` 생성자를 유지하며 이전 JSON에 새 필드가 없어도 읽을 수 있다. 기존 `benefits`, `limitUses`, `unallocatedBenefitGroups`의 의미는 유지한다. 제외 혜택을 0원 `BenefitValue`로 만들지 않는다.

부족 정보 코드는 `BRAND_MISSING` (`merchantId`), `MERCHANT_CATEGORY_MISSING` (`merchantCategoryCode`), `PURCHASE_ITEM_MISSING` (`purchaseItemCode`), `CHANNEL_MISSING` (`facts.CHANNEL:...`), `FACILITY_MISSING` (`facts.FACILITY:...`), `FACT_MISSING` (그 외 facts)다. 정책/한도/추가비용 미확인은 기존 차단 코드를 유지한다. 브랜드 제외 옵션으로 fallback 전환 대상의 미확인을 우회할 수 없으며 이때 `FALLBACK_CONDITION_UNKNOWN`으로 차단한다.

누락 정보는 계산이 실제 도달한 판정 경로를 설명한다. 기존 계산기의 조기 종료를 유지하므로 모든 카드 정책의 누락을 전수 검사한 목록은 아니다. 카드별 계산을 호출한 담당자가 카드 ID와 함께 표시한다.

예: 업종 혜택 500원은 계산했고 브랜드 혜택 1개를 제외한 경우(주요 필드 발췌):

```json
{
  "complete": true,
  "netBenefit": 500,
  "problems": [],
  "missingInformation": [{"benefitId": 1, "code": "BRAND_MISSING", "field": "merchantId"}],
  "excludedBenefits": [{"benefitId": 1, "reasonCode": "BRAND_MISSING"}],
  "simulationRules": [{
    "id": "YAMU_EXCLUDE_BRAND_DEPENDENT_BENEFIT", "version": 1,
    "description": "브랜드 미입력으로 판정할 수 없는 혜택을 제외한 모의 계산",
    "userCardId": null, "benefitId": 1,
    "appliedFields": ["excludeBrandDependentBenefits"]
  }]
}
```

같은 거래에서 옵션 OFF이면 `complete=false`, `netBenefit=null`, `problems=[{"benefitId":1,"code":"CONDITION_UNKNOWN"}]`, `missingInformation`에 `BRAND_MISSING`이 있고 `excludedBenefits`는 없다. 제외 후 남은 혜택이 없고 추가비용이 0이며 다른 계산 정보가 모두 확인되면 선택 범위의 순혜택은 0이다. 이는 실제 모든 혜택이 0이라는 뜻이 아니다.

## 조합 및 가정 처리

- BRAND OR CATEGORY에서 업종 경로가 충족되면 정상 계산하고 브랜드 누락을 문제로 기록하지 않는다.
- BRAND AND CATEGORY에서 업종은 맞고 브랜드만 미확인이면 제외한다. 다른 조건이 이미 불일치면 기존 확인된 불일치로 처리한다.
- 브랜드 제외조건을 판단 못하는 경우 해당 혜택을 제외하며 제외조건 자체를 삭제하지 않는다.
- 채널·시설·구매 항목 또는 조건 정책까지 미확인이면 브랜드 옵션으로 이를 해소하지 않는다. 같은 혜택에 브랜드와 채널 등이 함께 부족하면 계산 미완료로 남긴다.
- 독립 혜택, STACK, 기존 허용 조합은 제외 혜택의 금액·한도를 사용하지 않는다. MAX_ONE은 제외 후 선택 범위의 최대값이며 전체 혜택의 최대값이 아니다.
- PRIORITY_ONE은 브랜드 미확인 우선 혜택을 건너뛰지 않는다. 실제 다음 혜택 적용 여부가 불분명하면 기존 `PRIORITY_CONDITION_UNKNOWN`으로 차단한다.
- fallback은 기존 한도 전환 조건을 유지한다. 전환 대상이 미확인이면 제외 옵션으로 다시 진입시키지 않는다.
- 계산 옵션과 제외/누락 수집 상태는 호출마다 격리한다. 같은 계산기 인스턴스의 동시 요청·재시도도 상태를 공유하지 않는다. DB 쓰기나 외부 금융망 요청은 없다.

`BasicPurchaseSimulation.apply()`는 자동 호출하지 않는다. 일반 구매/시설 facts와 적용 근거는 담당자가 공급한다. 기존 함수는 실제 추가한 필드만 `YAMU_BASIC_PURCHASE.appliedFields`에 기록하므로 해당 규칙을 그대로 전달한다. 호출자는 적용하지 않은 가정을 규칙으로 넘기지 않아야 한다. 거래 규칙을 전달하고, 정책 규칙은 카드 공통 근거와 결과에 사용한 혜택의 근거를 반환한다. 제외하지 않은 경우 브랜드 제외 규칙을 생성하지 않는다.

## 검증

독립 검토 후 설명 오류 두 건을 보완했다. PRIORITY_ONE의 관계 확인용 재평가는 누락/제외 근거를 기록하지 않아 하위 우선순위 브랜드의 목록 순서가 결과 설명을 바꾸지 않는다. 정책 근거는 혜택 ID가 결과에 존재하는지만 보지 않고 반환 사유별로 실제 사용한 단계를 구분한다. 최소금액 미달에는 최소금액 근거만, 조건 불일치에는 유효기간·최소 기준 근거만 남기며 수행하지 않은 반올림 가정은 제외한다. 실제 계산 경로의 근거는 유지한다. 옵션 OFF/ON과 [A,B]/[B,A] 순서, 최소금액 미달/조건 불일치/실제 적용 경계를 추가해 관련 63건이 통과했다(실패/오류/생략 0). 재검토 요청 전의 구현자 검증이며 독립 검토 승인과 구분한다.

추천·추천 결과 JSON·결제 승인 회귀 412건 통과(실패 0, 생략 0). Testcontainers MySQL 기반 추천/Seed/승인 테스트 포함. 그 후 옵션 OFF fallback 코드 보존 경계를 보완하고 브랜드 제외와 다른 혜택의 시설 누락이 함께 있는 경우를 추가하여 최종 관련 61건도 통과했다(실패/오류/생략 0). 두 실행에는 중복 테스트가 포함되어 있으므로 건수를 합산하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.payment.approve.*'
.\gradlew.bat test --tests '*BrandMissingSimulationTest' --tests '*BenefitCompositionTest' --tests '*CardBenefitCalculatorTest' --tests '*RecommendationResultJsonTest'
```

새 테스트는 기본 OFF/이전 JSON 읽기, BRAND/CATEGORY/AND/OR/브랜드 제외조건, 다른 조건 불일치, 채널·시설·정책 누락, PRIORITY/fallback, 한도 미소비, 선택 범위 0과 null, 기존 가정 전달, 병렬 호출의 상태 격리를 검증한다. 순수 계산기에 새 결제 멱등성·금융망 재시도 로직은 추가하지 않는다.
