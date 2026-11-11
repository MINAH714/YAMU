# 브랜드 미입력 혜택 계산 인계 — S15P31E103-296

2026-11-05 승인 설계 및 2026-11-09 S15P31E103-359(B-07) 보완. 브랜드 조건이 있다는 이유로 혜택 전체를 일괄 제외하지 않는다. 명시적 시뮬레이션 옵션에서 브랜드 포함 경로 전체를 제외하고 남은 적용 가능 경로를 판정한다. 실제 결제 생성·한도/실적 갱신·잔고예보·앱 문구는 이 작업에 포함하지 않는다.

## 호출 계약

```java
// 가상 구매 공개 서비스: 기존 서버 조회·정책 조립·카드 추천을 재사용한다.
RecommendationService.Result result =
    recommendationService.recommendSimulation(userId, transaction, new CalculationOptions(true));
// result.recommendation().paymentId() == null
// evaluatedCards: 카드별 계산, rankedCards/selectedUserCardId: 기존 추천 결과

// 실제 POS/승인: 기존 호출 유지. 옵션이나 모의 가정을 자동 적용하지 않는다.
Calculation actual = calculator.calculate(transaction, policy);

// 시뮬레이션: 브랜드 제외 OFF + 누락 정보 설명
Calculation strict = calculator.calculate(transaction, policy, CalculationOptions.DEFAULT);

// 시뮬레이션 담당자가 명시적으로 선택한 경우에만 사용
Calculation scoped = calculator.calculate(transaction, policy, new CalculationOptions(true));
```

`CalculationOptions`의 필드는 `excludeBrandDependentBenefits`다. `false`가 기본이다. 기존 2인자 호출의 금액·완료 상태·문제 코드는 유지한다. 3인자 OFF 호출도 금액·완료 상태는 유지하되 차단 사유는 실제 원인으로 구체화한다. `merchantId`나 `facts`를 변경하지 않는다.

시뮬레이션 담당자는 인증된 userId와 서버에서 검증한 `RecommendationInput.Transaction`, 옵션을 공개 서비스에 전달한다. 카드별 정책은 서비스가 기존 조회·조립 로직으로 공급하며, 다른 사용자 카드가 섞이면 거부한다. 카드 식별은 기존 `CardEvaluation.userCardId`를 재사용한다. 새로운 결제 ID를 만들거나 `prepare()`를 호출하지 않는다. 실제 결제 경로는 기존 Selector 호출을 유지한다.

시뮬레이션 서비스는 결제 생성·저장·승인·한도 차감을 수행하지 않는다. 기존 결제 가능 여부 조회를 재사용하므로 체크카드 잔액의 캐시/금융망 조회는 발생할 수 있다. 해당 조회는 DB 트랜잭션 밖에서 수행해야 하므로 HTTP 호출자는 활성 DB 트랜잭션으로 이 메서드를 감싸지 않는다. 일반 구매·시설 facts와 실제 적용한 가정, 브랜드 선택 검증 및 앱 안내는 호출 담당자의 연결 범위다. 서비스 최상위 simulationRules는 계산 결과에 실제 남은 근거만 카드 ID와 함께 모은다.

## 결과 필드

| 필드 | 의미 |
|---|---|
| `complete` | 선택한 계산 범위에서 총 순혜택을 계산했는지 |
| `netBenefit` | 순혜택. 계산 불가면 `null`; 확인된 0과 구분 |
| `problems` | 계산을 막은 실제 원인(`benefitId`, `code`). 시뮬레이션은 누락 입력 코드와 기존 정책·실적 코드를 사용하며 완료 결과에서는 빈 목록 |
| `missingInformation` | `benefitId`, `code`, `field`. 판정 중 결과에 영향을 주는 누락 입력. 이 목록 자체는 실패 여부가 아님 |
| `excludedBenefits` | 실제 계산 범위에서 제외한 `benefitId`, `reasonCode=BRAND_MISSING`. 빈 목록이면 브랜드 제외가 실제 적용되지 않음 |
| `simulationRules` | 기존 `SimulationRule` 재사용. 전달받은 거래 가정, 사용한 정책 근거, 실제 브랜드 제외 근거 |

추가 목록은 JSON에서 비어 있으면 생략한다. 기존 5/6인자 `Calculation` 생성자를 유지하며 이전 JSON에 새 필드가 없어도 읽을 수 있다. 기존 `benefits`, `limitUses`, `unallocatedBenefitGroups`의 의미는 유지한다. 제외 혜택을 0원 `BenefitValue`로 만들지 않는다.

부족 정보 코드는 `BRAND_MISSING` (`merchantId`), `MERCHANT_CATEGORY_MISSING` (`merchantCategoryCode`), `PURCHASE_ITEM_MISSING` (`purchaseItemCode`), `CHANNEL_MISSING` (`facts.CHANNEL:...`), `FACILITY_MISSING` (`facts.FACILITY:...`), `FACT_MISSING` (그 외 facts)다. 정책/한도/추가비용 미확인은 기존 차단 코드를 유지한다. fallback 전환 대상의 미확인은 원래 원인으로 차단한다. 브랜드 누락은 `BRAND_MISSING`, 실적·최소 기준 누락은 `PERFORMANCE_OR_MINIMUM_UNKNOWN`이며 함께 부족하면 두 코드를 모두 problems에 남긴다. `FALLBACK_` 계열은 연결·전환 규칙 자체의 문제에만 사용한다.

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

같은 거래에서 옵션 OFF이면 `complete=false`, `netBenefit=null`, `problems=[{"benefitId":1,"code":"BRAND_MISSING"}]`, `missingInformation`에 `BRAND_MISSING`이 있고 `excludedBenefits`는 없다. 제외 후 남은 혜택이 없고 추가비용이 0이며 다른 계산 정보가 모두 확인되면 선택 범위의 순혜택은 0이다. 이는 실제 모든 혜택이 0이라는 뜻이 아니다.

## 조합 및 가정 처리

- BRAND OR CATEGORY에서 업종 경로가 충족되면 정상 계산하고 브랜드 누락을 문제로 기록하지 않는다.
- BRAND AND CATEGORY는 브랜드에 의존하는 경로 전체를 제외한다. 브랜드 조건만 삭제하여 업종 혜택으로 확대하지 않는다. 다른 조건이 이미 불일치면 기존 확인된 불일치로 처리한다.
- 브랜드 제외조건을 판단 못하는 경우 해당 혜택을 제외하며 제외조건 자체를 삭제하지 않는다.
- 브랜드 포함 경로를 제외한 후 적용 가능 경로가 없으면, 그 혜택의 채널·시설·추가 거래 조건도 더 이상 계산에 필요하지 않다. 해당 혜택은 `excludedBenefits`에 기록하고 `missingInformation`에는 `BRAND_MISSING`을 남긴다. 건너뛴 거래 조건은 카드 순혜택 계산의 차단 사유로 반환하지 않는다.
- 업종 등 브랜드 외 경로가 MATCHED 또는 UNKNOWN이면 해당 경로와 기존 제외 조건을 계속 판정한다. 남은 경로의 필수 정보가 누락되면 `complete=false`, `netBenefit=null`이며 그 경로에 필요한 누락 사유를 반환한다. 정책·유효기간·일정·최소금액·실적 미확인 보호 장치는 유지한다.
- 독립 혜택, STACK, 기존 허용 조합은 제외 혜택의 금액·한도를 사용하지 않는다. MAX_ONE은 제외 후 선택 범위의 최대값이며 전체 혜택의 최대값이 아니다.
- PRIORITY_ONE은 브랜드 미확인 우선 혜택을 건너뛰지 않는다. 시뮬레이션은 실제 누락 원인으로 차단하고, 기존 실제 결제 호출의 `PRIORITY_CONDITION_UNKNOWN`은 유지한다.
- fallback은 기존 한도 전환 조건을 유지한다. 전환 대상이 미확인이면 제외 옵션으로 다시 진입시키지 않는다.
- 계산 옵션과 제외/누락 수집 상태는 호출마다 격리한다. 같은 계산기 인스턴스의 동시 요청·재시도도 상태를 공유하지 않는다. DB 쓰기나 외부 금융망 요청은 없다.

`BasicPurchaseSimulation.apply()`는 자동 호출하지 않는다. 일반 구매/시설 facts와 적용 근거는 담당자가 공급한다. 기존 함수는 실제 추가한 필드만 `YAMU_BASIC_PURCHASE.appliedFields`에 기록하므로 해당 규칙을 그대로 전달한다. 호출자는 적용하지 않은 가정을 규칙으로 넘기지 않아야 한다. 거래 규칙을 전달하고, 정책 규칙은 카드 공통 근거와 결과에 사용한 혜택의 근거를 반환한다. 제외하지 않은 경우 브랜드 제외 규칙을 생성하지 않는다.

## 검증

### B-07 회귀 검사 (359)

기준 develop은 `88544c75`다. `SimulationBrandMissingDatabaseTest`는 실제 카탈로그 Seed 3개를 격리 Testcontainers MySQL에 적재하고 금융망 잔액 응답만 대체한다. 2026-11-03, 129,000원, HYPERMARKET/OFFLINE, Mr.Life·노리 체크, 전월 실적 각 50만 원·잔액 100만 원으로 검사한다.

- 수정 전: 브랜드 미선택 자동 추천은 `BENEFIT_DATA_INSUFFICIENT`, 추천 카드 없음. EMART 선택의 시뮬레이션/POS 추천 카드·순혜택 비교는 통과했다.
- 수정 후 검사: 브랜드 미선택 자동/직접 선택, 옵션 OFF의 카드별 순혜택 null 및 원인 유지, 제외 혜택·누락 정보·사용 가정의 일치, EMART 선택의 카드별 시뮬레이션/POS 금액 비교.
- 브랜드 전용 경로를 제외한 선택 범위에서 계산된 0원과 계산 불가 null은 구분한다. `RULE:UTILITY_PAYMENT`, `RULE:OFFICIAL_MERCHANT_ROUTE`를 false로 주입하지 않는다.
- 실적 인정액 관련 미확인 안내는 별도 계약이며 지우지 않는다. 운영 HTTP·앱 화면·실제 금융망을 검증한 것은 아니다.

2026-11-09 실행 결과:

| 범위 | 결과 |
| --- | --- |
| 추천 엔진 | 237건 통과 |
| POS·결제 추천 | 45건 통과 |
| 결제 승인 | 198건 통과 |
| 잔고예보·가상 구매 | 303건 중 302건 통과, 기존 기대값 1건 실패 |
| 기대값 보완 후 `SimulationPosFactsDatabaseTest` 재검사 | 2건 통과 |

전체 실행 783건의 오류·생략은 0건이다. 실패 1건은 LOCA LIKIT Play 브랜드 전용 주유 혜택에도 추가 미확인 사실이 있으면 카드 순혜택을 null로 기대하던 검사였다. B-07 계약에 따라 브랜드 전용 혜택을 제외한 순혜택 0원과 제외 안내를 검증하도록 수정한 뒤, 같은 클래스의 브랜드 선택 POS 비교까지 2건 모두 통과했다. 이 재검사 2건은 전체 783건에 포함되므로 합산하지 않는다. 전체 실행 뒤 프로덕션 코드는 변경하지 않았다.

새 B-07 Seed 검사 3건은 전체 실행에서 모두 통과했다. Mr.Life·노리 체크는 브랜드 미선택 옵션 ON에서 각각 순혜택 0원으로 계산 완료되고 자동 추천 카드가 반환된다. 옵션 OFF에서는 각각 순혜택 null 및 BRAND_MISSING/FACT_MISSING을 유지한다. EMART 선택 시 두 카드의 시뮬레이션/POS 순혜택과 자동 추천 결과가 일치한다.

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.payment.approve.*' --tests 'com.yamu.be.forecast.*'
.\gradlew.bat test --tests '*SimulationPosFactsDatabaseTest'
```

두 실행 모두 테스트 소스 전체 컴파일을 통과했고 `git diff --check`도 통과했다. 전체 실행 XML은 로컬 `be/build/b07-evidence/full-regression/`에 보존한다.

아래 기록은 296 당시의 검사 결과이며 359 검사 건수에 합산하지 않는다.

MR !134 후속 리뷰 반영: 결제 번호 없는 서비스 진입점, 사용자 카드 소유권 거부, 기존 실제 결제 결과 유지, 옵션 순차·동시 호출 격리 및 실제 사용한 근거 집계를 추가 검증했다. 브랜드 단독·실적 단독·동시 누락을 fallback과 일반 경로에서 확인했다.

추천·결제 추천·승인 전체 회귀 420건 중 419건 통과, 기존 사유 코드를 기대하던 테스트 1건 실패였다(오류/생략 0). 새 계약으로 해당 기대값을 수정하고 서비스 동시성 검증을 추가한 뒤 최종 관련 96건(브랜드 19, 서비스 34, 선택기 38, JSON 5)이 모두 통과했다(실패/오류/생략 0). 프로덕션 코드는 전체 회귀 이후 변경하지 않았다. 두 실행은 중복되므로 합산하지 않는다. 서비스 테스트는 DB 조회 mock 기반이며 금융망·전체 앱 화면 검증을 의미하지 않는다.

아래 63/412/61건은 후속 리뷰 반영 이전의 실행 기록이다.

독립 검토 후 설명 오류 두 건을 보완했다. PRIORITY_ONE의 관계 확인용 재평가는 누락/제외 근거를 기록하지 않아 하위 우선순위 브랜드의 목록 순서가 결과 설명을 바꾸지 않는다. 정책 근거는 혜택 ID가 결과에 존재하는지만 보지 않고 반환 사유별로 실제 사용한 단계를 구분한다. 최소금액 미달에는 최소금액 근거만, 조건 불일치에는 유효기간·최소 기준 근거만 남기며 수행하지 않은 반올림 가정은 제외한다. 실제 계산 경로의 근거는 유지한다. 옵션 OFF/ON과 [A,B]/[B,A] 순서, 최소금액 미달/조건 불일치/실제 적용 경계를 추가해 관련 63건이 통과했다(실패/오류/생략 0). 재검토 요청 전의 구현자 검증이며 독립 검토 승인과 구분한다.

추천·추천 결과 JSON·결제 승인 회귀 412건 통과(실패 0, 생략 0). Testcontainers MySQL 기반 추천/Seed/승인 테스트 포함. 그 후 옵션 OFF fallback 코드 보존 경계를 보완하고 브랜드 제외와 다른 혜택의 시설 누락이 함께 있는 경우를 추가하여 최종 관련 61건도 통과했다(실패/오류/생략 0). 두 실행에는 중복 테스트가 포함되어 있으므로 건수를 합산하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.payment.approve.*'
.\gradlew.bat test --tests '*BrandMissingSimulationTest' --tests '*BenefitCompositionTest' --tests '*CardBenefitCalculatorTest' --tests '*RecommendationResultJsonTest'
```

새 테스트는 기본 OFF/이전 JSON 읽기, BRAND/CATEGORY/AND/OR/브랜드 제외조건, 다른 조건 불일치, 채널·시설·정책 누락, PRIORITY/fallback, 한도 미소비, 선택 범위 0과 null, 기존 가정 전달, 병렬 호출의 상태 격리를 검증한다. 순수 계산기에 새 결제 멱등성·금융망 재시도 로직은 추가하지 않는다.
