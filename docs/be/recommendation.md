# 카드 추천 엔진 인계

## 앱 안내용 이유 코드 (2026-11-09 대조)

[MR !197](https://lab.ssafy.com/s15-final/S15P31E103/-/merge_requests/197)의 `docs/be/forecast.md` 표(`dadc363fc10141e8790afb99338149272477ab64`)를 엔진 `develop`의 `59abaf8e3611801a72e986284e241cbba15de971` 구현과 대조한 정의다. 아래는 해당 표의 코드와 관련 결제 가능 판정·데이터 사유를 정리한 것으로, 엔진의 모든 조합 계산 코드를 열거한 목록은 아니다. 계산 불가 혜택을 제외할지 여부 등 계산 정책은 이 문서 보강에서 변경하지 않는다.

### 전달 위치와 해석

- **평가 사유**: 카드 평가의 `reasonCode`. `BENEFIT_DATA_INSUFFICIENT`는 요약이므로 상세 계산 문제·빠진 정보도 함께 본다.
- **계산 문제**: 계산 결과의 `problems`에 혜택 ID와 코드가 담긴다. **빠진 정보**는 조건 평가의 `missingInformation`에 `code`와 `field`로 담긴다. 가상 구매 API는 이 정보를 자체 응답으로 옮기므로 내부 객체와 API 응답 모양은 같지 않다.
- **데이터 문제**: `RecommendationDataIssue`의 코드와 `retryable`. 데이터 문제가 있다고 항상 카드 혜택 계산 전체가 실패하는 것은 아니다(예: 동률 판단용 실적 조회 실패, 신용한도 확인 생략).
- 코드는 HTTP 오류 코드가 아니다. 결제 불가(`UNAVAILABLE`), 판정 불가(`UNKNOWN`), 확인 생략을 구분한다. 계산하지 못한 혜택을 확정된 0원으로 안내하지 않는다. 알 수 없는 새 코드는 일반 안내 문구로 처리한다.

### 결제 가능 여부와 카드 평가

발생 위치: `RecommendationAvailabilityReader.prepare`와 잔액 조회 처리. 별도로 표시한 요약 사유는 `RecommendationSelector.evaluate`, 기본값은 `RecommendationService`에서 만든다.

| 코드 | 뜻과 발생 조건 |
|---|---|
| `CARD_NOT_ACTIVE` | 카드 등록 상태가 ACTIVE가 아니어서 결제할 수 없음. |
| `CARD_EXPIRED` | 카드사 상태가 만료여서 결제할 수 없음. |
| `CARD_SUSPENDED` | 카드사 상태가 정상도 만료도 아니어서 결제할 수 없음. |
| `CHECK_CARD_INSTALLMENT_NOT_SUPPORTED` | 체크카드로 할부를 요청해 결제할 수 없음. |
| `INSUFFICIENT_BALANCE` | 체크카드 출금 계좌의 조회된 현재 잔액이 거래 금액보다 적음. 미래 구매일의 예상 잔액 부족이라는 뜻은 아님. |
| `PAYMENT_ACCOUNT_DISCONNECTED` | 출금 계좌 연결이 해제되어 결제할 수 없음. |
| `PAYMENT_ACCOUNT_UNKNOWN` | 연결된 출금 계좌를 사용자 소유 계좌 조회 결과에서 찾지 못해 결제 가능 여부를 모름. |
| `UNPAID_BILL` | 신용카드에 미납 청구서가 있어 결제할 수 없음. 가상 구매의 사전 검증·HTTP 처리는 forecast 계약을 따른다. |
| `BILLING_CYCLE_UNSUPPORTED` | 신용카드 결제일이 없거나 지원하는 청구 주기가 아니어서 판정할 수 없음. |
| `CREDIT_LIMIT_EXCEEDED` | 한도 조회 결과가 초과여서 결제할 수 없음. |
| `CREDIT_LIMIT_UNKNOWN` | 한도 조회 결과가 미확인이어서 결제 가능 여부를 모름. |
| `CREDIT_LIMIT_CHECK_SKIPPED` | 한도 확인을 생략함. 엔진에서는 AVAILABLE이며 데이터 안내에도 남긴다. 한도가 충분하다고 확인한 뜻도, 결제 불가 사유도 아님. |
| `BALANCE_READ_RETRYABLE` | 잔액 조회가 시간 초과·금융망 오류 등으로 실패함. 재시도로 복구될 수 있으나 성공을 보장하지 않음. |
| `BALANCE_ACCESS_UNKNOWN` | 잔액 접근 오류 등으로 잔액을 확인하지 못함. 재시도 가능 오류로 분류되지 않은 경우. |
| `PAYMENT_AVAILABILITY_UNKNOWN` | 서비스에 해당 카드의 결제 가능 판정이 공급되지 않아 UNKNOWN으로 처리함. |
| `PRODUCT_UNKNOWN` | 상품 정보가 없어 결제 가능 여부를 모르거나, 선택기에 카드 종류가 없어 비교하지 못함. 선택기는 계산 문제에도 같은 코드를 남김. |
| `BENEFIT_DATA_INSUFFICIENT` | 선택기가 카드 혜택 계산을 끝내지 못했다는 요약. 실제 원인은 상세 코드에서 확인해야 함. |
| `BASE_AMOUNT_INVALID` | 혜택 계산 기준 금액이 거래 금액보다 큼. 계산기가 생성하며 선택기도 이 문제를 평가 사유로 전달함. |

### 혜택 조건·한도를 판정하지 못한 이유

발생 위치: `BenefitConditionEvaluator.explain`·`explainTarget`, `CardBenefitCalculator.blockingCauses`·`performanceUnknown` 및 한도 계산. 상세 원인은 호출 옵션과 판정 경로에 따라 일반 `CONDITION_UNKNOWN` 대신 여러 문제 코드로 전달될 수 있다.

| 코드 | 뜻과 발생 조건 | 빠진 정보의 `field` |
|---|---|---|
| `BRAND_MISSING` | 브랜드 조건을 판정하는 데 브랜드가 없음. 브랜드 미선택 제외 옵션으로 해당 혜택이 제외된 경우와 계산을 막는 경우는 구분해야 함. | `merchantId` |
| `MERCHANT_CATEGORY_MISSING` | 업종 조건을 판정하는 데 업종이 없음. | `merchantCategoryCode` |
| `PURCHASE_ITEM_MISSING` | 구매 품목 조건을 판정하는 데 품목이 없음. | `purchaseItemCode` |
| `CHANNEL_MISSING` | 필요한 결제 채널 사실을 모름. | `facts.CHANNEL:<키>` |
| `FACILITY_MISSING` | 필요한 매장 시설 사실을 모름. | `facts.FACILITY:<키>` |
| `FACT_MISSING` | 그 밖의 필수·제외 거래 사실을 모름. 사용자가 직접 입력할 수 있는 정보라는 뜻은 아님. | `facts.<사실 키>` |
| `CONDITION_UNKNOWN` | 조건 정책·현재 버전·유효기간 등의 확인이 부족하거나, 조건을 판정하지 못한 구체적 이유를 특정하지 못함. 공과금 전용 코드가 아님. | 계산 문제 자체에는 없음 |
| `PERFORMANCE_OR_MINIMUM_UNKNOWN` | 최소 실적 또는 최소 결제 금액이 없거나, 최소 실적이 양수인데 전월 실적을 모름. 실적이 기준에 미달한다고 확인한 뜻은 아님. | 계산 문제 자체에는 없음 |
| `LIMIT_UNKNOWN` | 연결된 한도 정보가 없거나 최대값·사용량을 몰라 계산하지 못함. 건별 한도는 사용량이 없어도 이 사유로 막지 않음. | 계산 문제 자체에는 없음 |

예를 들어 공과금 여부에 필요한 사실 값이 없다면 상세 설명 경로에서는 `FACT_MISSING`과 `facts.<해당 키>`로 식별한다. `CONDITION_UNKNOWN`만 보고 앱이 “공과금 여부를 몰라요”라고 단정하면 안 된다. 확인된 조건 불일치와 정보 부족도 다르다. 조건에 맞지 않아 혜택이 없는 경우를 “정보가 없어 계산하지 못함”으로 안내하지 않는다.

### 데이터 조회·정책 조립 문제

발생 위치: `RecommendationService`의 조회·정책 조립 및 `RecommendationAvailabilityReader`의 조회 처리.

| 코드 | 뜻과 발생 조건 | `retryable` |
|---|---|---|
| `DATABASE_READ_RETRYABLE` | 일시적인 DB 조회 실패. 후보 카드 목록을 읽기 전이면 카드별 결과 대신 같은 코드를 가진 `RecommendationReadException`이 발생하고, 후보를 확보한 뒤에는 데이터 문제로 남길 수 있음. | true |
| `PREVIOUS_PERFORMANCE_MISSING` | 전월 실적이 필요한 카드의 해당 월 실적 기록이 없음. 확인된 실적 0원과 구분함. | false |
| `POLICY_DATA_INVALID` | 정책 조립 중 잘못된 데이터 조합으로 `IllegalArgumentException`이 발생해 카드 정책을 만들지 못함. | false |
| `PRODUCT_MISSING` | 등록 카드에 대응하는 상품 행이 조회 결과에 없음. `PRODUCT_UNKNOWN`과 관련되지만 서비스 데이터 누락을 나타내는 별도 코드임. | false |
| `RECOGNITION_BEFORE_AMOUNT_MISSING` | 동률 판단에 쓰는 실적의 거래 전 인정 금액을 확인하지 못함. | false |
| `RECOGNITION_AGGREGATE_READ_RETRYABLE` | 동률 판단용 실적 집계를 일시적인 DB 오류로 읽지 못함. 이미 읽은 혜택 정책까지 폐기한다는 뜻은 아님. | true |

`retryable=false`는 영구 복구 불가라는 뜻이 아니라 엔진이 일시적 조회 실패로 분류하지 않았다는 뜻이다. 원본 데이터 보완이나 계좌 재연결 등 별도 조치가 필요할 수 있다.

### 가상 구매가 만드는 코드와의 경계

MR !197의 `RECOMMENDATION_READ_FAILED`, `CARD_NOT_EVALUATED`는 가상 구매가 엔진 결과·예외를 해석해 만드는 코드이므로 엔진 발생 코드로 취급하지 않는다. `MERCHANT_CATEGORY_MISSING`은 엔진의 조건 평가에서도 생기고, 가상 구매가 업종·브랜드 미선택으로 엔진을 호출하지 않을 때도 생긴다. API 응답·사전 검증·앱 문구는 [forecast.md](forecast.md)의 가상 구매 계약을 함께 따른다.

이유 코드 정의는 위 MR 문서와 명시한 엔진 커밋을 대조했다. 추가로 MR !201 수정본 `5be379cb`에서 `MrLifeUtilityReproductionDatabaseTest`를 실행했다. 토요일 12시(KST), 129,000원 일시불, 전월 실적 50만 원, 월 한도 사용 0원 조건에서 이마트는 5,000원 계산 완료, 대형마트 업종만 선택하면 브랜드 의존 혜택 제외 후 0원 계산 완료, KT 브랜드만 선택하면 월납 혜택의 `FACT_MISSING`(`facts.RULE:UTILITY_PAYMENT`)으로 비교 불가였다. `UTILITY_PAYMENT`를 임의로 공급하지 않았다. 세 사례와 브랜드 미선택 회귀 1건, 총 4건 통과(실패·오류·생략 0). 이 결과는 이전 develop이 아닌 해당 수정본 기준이다. 운영 코드·시드·계산 정책은 변경하지 않았다.


2026-11-03 Docker 활성화 후 관련 테스트 253개 전부 통과(실제 MySQL 테스트 42개 포함), 실패·오류·생략 0을 확인했다. 보강한 JSON NULL/0 실제 저장 테스트도 별도 1개 통과했다. 트리거 생성에 필요한 테스트 컨테이너 설정과 배포 전제, 실행 범위는 [조합 연결 검증 기록](benefit-composition.md)에 구분한다. 개발/운영 DB 적용이나 상품 정책 전체 확인을 뜻하지 않는다.

결제 조건과 확인된 카드 정보를 전달하면 카드별 예상 혜택을 계산하고, 이번 결제의 순혜택이 가장 큰 카드를 선택한다. 일부 카드의 정보를 확인하지 못해도 확인된 후보 중 추천하고, 비교하지 못한 카드와 사유를 함께 제공한다. 실제 POS HTTP 접수부터 앱 화면까지 연결한 기능은 아니다. 2026-11-03 승인 설계의 대상/적용 그룹·fallback·총액/미배분·실제 추천 포트·JSON 보존 호출 계약은 [조합 연결 문서](benefit-composition.md)를 따른다.

| 구분 | 현재 범위 |
|---|---|
| 완료 | 혜택 조건 판단·예상 혜택 계산, 최대 순혜택 선택·동률 처리, 일부 정보 부족·0원 안내, 추천 근거 제공 |
| 완료 | 단일 인정월의 실적 보조 정보 구성과 기존 월 지정 실적 조회 재사용 코드 |
| 구현 | 내부 `RecommendationService`의 카드·상품·혜택·한도·사용량 조회 → 입력 구성 → 기존 Selector 호출 |
| 구현 | AND/OR 대상 경로, STACK/MAX_ONE/PRIORITY_ONE, 현대 M 원금 전환, 미배분 총액, 실제 결제 포트·JSON 저장/조회 계약 |
| 남은 미확인 | 상품 정책 완전성·명시되지 않은 중첩 관계·계산 순서, 아래에 명시한 일부 실적 귀속 근거·거래 사실 |
| 후속 결제 연결 | POS 접수에서 보존 계약 호출, 저장 트랜잭션 커밋 후 앱 응답, 승인 연결 |

## 결제 담당이 호출할 때

서버가 POS의 금액·결제 방식·개월 수와 거래 조건을 검증하고 보존한 뒤 내부에서 호출한다. 독립 추천 미리보기 HTTP API는 없다.

```java
// recommendationService는 같은 BE의 Spring 빈이다. HTTP API가 아니다.
var transaction = new RecommendationInput.Transaction(
        preservedAmount, preservedPlan, preservedMonths, preservedOccurredAt,
        preservedMerchantId, preservedMerchantCategoryCode, verifiedFacts);
var command = new RecommendationCommand(paymentId, serverUserId, transaction, Map.of());
RecommendationService.Result result = recommendationService.recommend(command);
RecommendationSelector.Result recommendation = result.recommendation();
// result.issues(): 입력 조회·확인 사유. result.cards(): 이미 조회한 상품명·별칭·끝 4자리.
```

결제 가능 상태는 추천 서비스가 사용자 소유 카드, 상품과 계좌, 미납 청구서, 청구 가능 조건과 기존 CreditLimitSource를 조회해 생성합니다. 기존 availability 입력은 호환용이며 서버 판정을 덮어쓰지 않습니다. POS가 카드 목록이나 판정을 공급할 필요는 없습니다. 체크카드 잔액은 기존 AccountBalanceService의 부분 조회 결과를 사용하고 같은 계좌는 한 번 읽습니다. 실패한 계좌만 미비교로 남기며 잔액 0원으로 대체하지 않습니다. 신용한도 SKIPPED는 다른 조건이 맞으면 후보가 되지만 한도 충분 확인으로 바꾸지 않고 CREDIT_LIMIT_CHECK_SKIPPED 사유를 남깁니다.

일반 `recommend` 호출은 DB 트랜잭션 밖에서 시작합니다. POS는 저장 전에 `cardRecommender.prepare(request)`로 사용자와 거래 조건을 전달해 카드 상태, 잔액, 혜택 정책, 사용량과 실적 조회를 마칩니다. 저장 트랜잭션 안에서는 발급된 결제 ID만 전달해 준비한 입력으로 한 번 계산합니다.

접수 추천은 조회 시점의 예상값입니다. 조회와 저장 사이에 사용량이 바뀌면 이 요청은 준비한 값으로 계산하고 새 요청은 다시 조회합니다. 추천은 한도를 예약하지 않으며 최종 승인 시 최신 상태 확인과 실제 사용량 반영은 결제 담당의 책임입니다.

**현재 DB 경로:** `CardRecommender → DatabaseCardRecommender → RecommendationService → Repository/Loader → RecommendationInputAssembler → RecommendationSelector/CardBenefitCalculator`가 연결돼 있다. 2026-11-03 일괄 승인된 `YAMU_SIM_CARD_POLICY/1`은 Seed 17종·56개 혜택의 버전 1 및 2026년 11월 한국 거래에만 누락 계산 기준을 제공한다. 실제 상품 정책을 확인한 것으로 표시하지 않고 `simulationRules`에 적용 필드·카드·혜택을 남긴다. 세부 값과 제한은 [조합 연결 문서](benefit-composition.md)의 모의 정책 표를 따른다. 기존 5인자 조립기 테스트는 모의 범위를 공급하지 않으므로 기존 미확인을 유지한다.

POS 접수와 승인 중 추천 재생성은 위 서버 판정을 사용합니다. 최초 구매 항목과 추가 검증 사실은 별도 입력이며 미공급 사실을 일괄 false로 만들지 않습니다. DB JSON 보존은 DatabaseRecommendationResultStore, 결제 진행 중 Redis 저장은 RecommendationSnapshotStore입니다. 앱 조회 응답에는 미배분 상세의 null과 확정 그룹 총액을 함께 전달합니다.

## 반환하는 결과

추천 상태, 선택 카드, 예상 순혜택과 선택 근거를 제공한다. 정상 계산 후보는 `rankedCards`, 전체 카드별 판정은 `evaluatedCards`에서 확인한다. 전체 판정에는 확인된 결제 불가 카드와 미비교 카드가 구분되어 있고, `uncomparedCards()`로 미비교 카드·사유만 가져올 수 있다. 계산 정보가 부족하면 상세 사유도 함께 제공한다.

| 상태 | 의미와 연결 시 처리 |
|---|---|
| READY | 모든 비교 대상이 확인된 추천. 선택 카드와 예상 혜택을 표시한다. |
| PARTIAL_READY | 확인된 후보 중 추천. 미비교 카드와 확인하지 못한 정보의 사유를 안내한다. |
| DATA_INSUFFICIENT | 정보 부족으로 정상 계산된 후보가 없다. 선택 카드·확정 순위가 없다. |
| NO_ELIGIBLE_CARD | 확인된 결제 불가 카드만 존재하거나 후보가 없다. |

확인된 후보가 모두 0원이면 `noBenefit=true`와 안내를 제공한다. 미비교 카드가 있으면 PARTIAL_READY를 유지한다. 미비교 카드는 0원 카드나 확인된 결제 불가 카드로 취급하지 않으며 추천 선택·자동 승인 대상으로 사용하지 않는다.

순혜택이 정확히 같은 최대 후보에만 기본 카드 → 최소 실적 기준 신규 충족 → 저장된 카드 순서를 적용한다. 신규 충족은 결제 전 누적액 < 최소 기준 ≤ 누적액 + 이번 인정액이다. 실적 보조 정보가 하나라도 미확인이면 남은 동률 후보 전체에서 실적 기준만 생략한다. 이것만으로 PARTIAL_READY가 되지는 않는다.

고정 정렬은 기존 카드 관리와 같은 기준이다. 지정된 displayOrder가 있는 카드를 먼저 두고 작은 순서값부터 비교한다. 같은 값이거나 순서 미지정이면 등록 시각 → ID 오름차순으로 결정한다. 등록 시각까지 NULL이면 기존 MySQL ASC와 같이 먼저 두고 ID로 결정한다. 모든 경우 조회·입력 목록 순서에 의존하지 않는다. 이 보조 기준은 최대 동률의 최종 선택에만 적용하며, 선택 후 나머지 비교 목록은 기존 순혜택 내림차순·ID 오름차순을 유지한다.

계산 기준액이 원 결제액보다 큰 카드에는 BASE_AMOUNT_INVALID 사유를 제공하고 NOT_COMPARED로 처리한다. 정상 후보가 있으면 PARTIAL_READY로 나머지 후보 중 추천하고, 정상 후보 없이 미확인이 남으면 DATA_INSUFFICIENT다. 잘못된 기준액을 0원으로 바꾸지 않는다.

혜택 시작일·종료일 NULL은 미확인이다. verified/currentVersionVerified가 true여도 누락 날짜를 적용 가능으로 간주하지 않는다. 종료일 9999-12-31은 종료 제한 없음이 확인된 입력이고, 일반 종료일은 해당 날짜부터 적용하지 않는다. 시작일 미확인은 임의 날짜로 채우지 않는다. 이 변경은 엔진 입력의 의미이며 기존 데이터 변경·DB 이관은 포함하지 않았다. 종료일 저장 규칙과 실제 상품 데이터 보완은 공급 작업에서 별도로 연결해야 한다.

## 실적 입력을 구성하는 방법

`RecommendationPerformanceAssembler`는 확인된 인정월별로 기존 `CardActivityReader.recognizedAmounts(ids, month)`를 호출하고 단일 `period`의 보조 정보를 만든다. 두 클래스와 필요한 메서드·Recognition만 공개해 추천 서비스가 재사용한다. 카드 목록 화면의 현재 월 조회는 변경하지 않았다.

- 소유권을 확인한 카드 ID와 상품 정책에서 확인한 인정월·인정액·그 기간의 최소 기준을 전달한다.
- 서버 현재 월을 인정월로 대신 사용하지 않는다. 같은 카드의 중복 입력은 거절한다.
- 인정월이 확인되면 다른 항목이 미확인이어도 그 월의 누적액을 읽는다. 인정월 자체가 미확인이면 월 조회를 생략한다. 집계 행 누락은 null로 유지하며 확인된 0원과 구분한다. 음수는 거절한다.
- 새 서비스는 DB의 `min_performance_amount`(최소 전월 실적)·실적 구간 자료가 필요한 카드에 대해 거래 시각의 한국 날짜를 기준으로 이전 달 집계 행을 일괄 조회해 `previousMonthPerformance`로 전달한다. 서버 현재 월은 사용하지 않는다. 이 저장 의미에 따른 참조 자료를 읽어도 상품 약관 검증 완료가 되는 것은 아니며 complete=false는 유지한다. 상품별 별도 기준 기간은 확인 근거가 공급되기 전에는 지원한다고 보고하지 않는다.
- 동률용 정보는 `RecommendationRecognitionResolver`가 수집 원본에서 확인해 정규화한 `src/main/resources/recommendation/performance-policies.json`과 검증된 거래 사실로 산출한다. 모든 카드에 null을 고정 전달하던 처리는 제거했다. 최소 기준은 `card_benefit.min_performance_amount`의 최솟값으로 만들지 않는다. 실적 인정액을 계산하는 별도 기존 코드는 현재 브랜치에 없으며, 확인한 결제 승인 브랜치의 0원은 임시 반환값이어서 재사용하지 않았다. 할인 여부가 필요한 상품에서는 기존 `CardBenefitCalculator`를 재사용하고, 계산 실패를 할인 없음으로 간주하지 않는다.

### 동률 실적의 근거와 실제 공급

원본은 조달된 `YAMU-카드20종-통합데이터.xlsx`의 cards·performance(133행)·benefits·review_facts·sources다. 파일 SHA-256은 `b642be77dfdfd28bcc0e02b32a80a41f9dca11fda3939cfd29d32b82d0ef6afa`이며, 정규화 파일에 각 상품의 원본 행/검토 문단 ID를 보존했다. 시드 생성기는 performance 원본을 읽지 않고 혜택·한도 자료만 옮겼으므로 DB 컬럼만 보고 공급 불가라고 판단할 수 없다. 이 원본은 공급자가 검토한 자료/공식 검토 요약이며 모든 행이 공식 원문 직접 인용인 것은 아니다. 처음·Mr.Life는 이번 조사에서 현재 공식 상품 페이지도 직접 확인했다. 정규화는 서술형 문자열을 실행하는 엔진이 아니라 사람이 근거를 대조한 유한한 상품 규칙이다.

실제 경로는 `Service → RecognitionResolver(상품 원본+거래) → RecommendationPerformanceAssembler → CardActivityReader.recognizedAmounts(ids, period) → PerformanceProgress → CardCandidate.performance → Selector`다. 상품은 카드사·정확한 상품명·신용/체크 구분으로 연결하며 다른 상품에 규칙을 빌려주지 않는다.

| 상품/원본 위치 | 연결한 최소 기준·인정 규칙 |
|---|---|
| 신한 처음 / A-fact-004·009·010, 현재 공식 상품의 전월 이용금액 기준 | 일반 최소 30만원. 국내 승인 거래는 거래 한국 월, 실물 후불교통은 다음 인정월·모바일 후불교통은 같은 월. 실적 제외를 적립 제외와 분리해 무이자/할인 적용만으로 실적을 0으로 만들지 않음 |
| Deep Oil / performance 1~10 | 일반 최소 30만원. 국내 거래 월·교통 다음 월, 주유/LPG 등 확인된 제외만 0. DB 시드에는 미적재된 상품이므로 원본 공급 규칙 존재와 실제 보유 카드 존재는 구분 |
| KB 청춘대로 톡톡 / performance 1~8 | 일반 최소 30만원. 국내 승인 월, 할인 적용 거래는 전체 인정액 0. 무승인 거래를 버스/지하철 업종 전체로 확대하지 않음 |
| LOCA LIKIT Eat / A-fact-005·008~010·013 | 일반 최소 40만원, 할부는 승인 월 전액 기준. 무이자 할부·상품 할인 적용 거래는 0. 해외/통신 자동납부 접수일은 요청 시각으로 대체하지 않음 |
| LOCA LIKIT Play·Shop / performance 1~13 | 일반 최소 40만원. 무이자 할부·할인 거래 전체 제외. 전기/수도/시외버스의 미확인 범위는 해당 거래에서만 미확인으로 남김 |
| 현대 M ME4 / A-fact-009·010, 상품의 기본 실적 구간 | 일반 최소 50만원. 최초 승인 월 전액. 포인트 적립 제외인 전기·도시가스·관리비를 실적 제외로 복사하지 않음 |
| KB 노리 체크 / A-fact-004·008·017 | 일반 최소 20만원. 국내 승인 월, 후불교통·해외·무승인·정부지원금·취소 제외. 체크카드 할부는 기존 결제 가능 판정에서 먼저 제외 |
| Mr.Life / A-fact-006·007, 현재 공식 인테이크 안내 | 인테이크의 양수 유실적 조건은 정수 원화 최소 1원으로 표현. 일반 30만원 문턱으로 덮어쓰지 않음. 일시불의 확인된 제외/인정액은 연결하되 일반 귀속 사건·할부 전액 근거는 아래 미확인 유지 |
| 원더카드 2.0 DAILY / performance BASIS·TIER·제외·할부 | 일반 최소 40만원과 확인된 인정/제외액은 연결. 할부는 명시된 승인 월 전액 규칙을 적용한다. 일반 일시불 귀속 사건의 승인/접수 구분은 미확인. 시드 미적재 상태도 유지 |
| ZERO 할인/포인트·LOCA LIKIT 2.0·트래블로그·iD SIMPLE·iD ON·taptap O·노리2·프렌즈·Point Plan / BASIS=NONE 또는 실적 없는 기본/주말 혜택 원문 | 최소 기준 0원인 혜택 존재를 확인. 비음수 누적액에서 `before < 0`은 불가능하므로 신규 충족은 확정 false. 불필요한 인정월·누적액·거래 인정액을 0으로 조작하지 않고 null 그대로 보존하며, 이 경우만 `isKnown()`이 신규 충족 불가를 확정한다 |

거래 사실의 키 부재는 false가 아니다. 정규화 파일의 `excludedFacts`는 TRANSACTION_KIND 코드와 수집 원문의 의미를 대응한 목록이며, 공급자가 해당 사실의 true/false를 확인해야 한다. REGION:DOMESTIC, TRANSACTION_KIND:UNAPPROVED_VOUCHER도 확인해야 승인 월을 쓸 수 있다. 교통 예외에는 `PERFORMANCE:POSTPAID_TRANSIT`·`PERFORMANCE:MOBILE_POSTPAID_TRANSIT`, 롯데 통신 예외에는 `PERFORMANCE:TELECOM_AUTOPAY`를 사용한다. 카드사 등록일/최초 발급일을 앱의 registeredAt으로 추정하지 않으므로 일반 최소 기준을 적용하려면 `PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:<card_id>=false`가 확인돼야 한다. 원본의 신규 실적 유예 적용이 true로 확인되면 최소 기준 0원으로 신규 충족 대상 아님을 확정하고, 적용 여부가 미확인이면 최소 기준만 null로 남긴다. 이 기술 키들은 새 HTTP API가 아니라 기존 Transaction.facts의 서버 확인 사실이다. 실제 결제 측 공급이 아직 연결됐다는 뜻은 아니다.

할인 거래 제외 상품은 `PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:<card_id>`의 확인된 판정 또는 기존 계산기의 확인된 할인 결과를 사용한다. 양쪽 모두 미확인이면 인정액은 null이다. 반대로 상품권 등 확인된 실적 제외에 해당하면 다른 사실이 미확인이어도 인정액 0은 확정할 수 있다. 일부만 할인돼도 거래 전체 제외 규칙을 지키며 할인액만 빼는 새 산식을 만들지 않는다.

### 남은 미확인 — 상품·항목별

| 구체적인 자료/입력 부족 | 영향 |
|---|---|
| Mr.Life: 수집 A-fact-006·현재 특화 안내는 전월 유실적·제외·교통/해외 예외를 제시하지만, 일반 거래의 승인/매입 기준 사건과 할부 전액 반영 시점을 명시한 원문은 확보하지 못함 | 최소 1원과 일시불 인정액을 보존하되 인정월/할부 인정액을 임의 생성하지 않음 |
| 원더카드 DAILY: performance BASIS는 전월 1일~말일을 제시하나 일반 국내 일시불의 승인/접수 기준 사건이 없음. school_fee 확장은 UNVERIFIED | 최소 40만원·확인된 인정액·명시된 할부 승인 월은 유지. 일반 일시불 인정월 및 해당 유치원 납입금 범위만 미확인 |
| Play·Shop: performance의 전기/수도/시외버스 범주가 UNVERIFIED | 카페 거래처럼 무관한 거래는 계속 산출하고 해당 범주만 null 유지 |
| 처음·Deep Oil 및 KB/LOCA 등의 해외 매입/접수일, LOCA 이동통신 자동납부 접수일이 현재 Transaction에 없음 | 해당 거래의 인정월만 미확인. 서버 현재 월이나 요청 월로 대체하지 않음 |
| 실제 거래의 신규 유예 여부·실적 제외 사실·상품별 할인 적용 여부가 제공되지 않음 | 해당 최소 기준/인정액만 미확인. 정상 산출되는 다른 필드와 인정월 누적액을 지우지 않음 |
| 해당 카드·인정월의 집계 행 부재 | beforeAmount=null, 0으로 대체하지 않음. 월별 Reader는 소유권 확인된 ID만 받고 해당 인정월만 조회 |

신규 유예 규칙을 임의로 정하거나 스키마를 추가하지 않았다. 위 항목은 이번 요청에서 조사한 구체적인 미확인이며, 동률 공급 전체를 후속으로 이관한 것이 아니다. 이 원본 스냅샷이 모든 과거 약관 버전/미래 변경을 보증하지는 않는다. 거래 시점과 다른 약관 버전의 인정을 재현하려면 해당 버전의 원본 근거가 추가로 필요하다.

## 실제 DB 조회와 입력 구성

`RecommendationService` → 기존 카드/상품 Repository → `RecommendationPolicyLoader` → `RecommendationInputAssembler` → 기존 Selector 순서다. 엔티티·Repository를 별도 서비스나 범용 규칙 계층으로 만들지 않는다.

| 자료 | 조회 및 변환 |
|---|---|
| 카드·상품 | 기존 `findOrdered(userId, ACTIVE/INACTIVE)`와 `findAllById(productIds)`. 사용자 소유권을 확인하고 상품을 inner join하여 누락 카드를 숨기지 않는다. 상품 누락은 종류 null로 보존하고 PRODUCT_UNKNOWN으로 미비교 처리한다. 확인된 결제 불가가 먼저 적용된다. |
| 혜택 | 신규 `CardBenefit` 등 5개 JPA 엔티티와 Repository. 상품별 유효 가능 버전을 일괄 조회하며 미래·종료 버전을 제외한다. NULL 날짜는 남겨 미확인으로 처리하고 같은 논리 코드의 중첩 버전을 임의 선택하지 않는다. |
| 조건 | BRAND/CATEGORY/ALL 대상과 CONDITION 포함·제외, 엄격한 요일·시간 JSON을 변환한다. NULL JSON의 저장 의미는 추가 일정 제한 없음이지만, 약관 전체 확인을 보증하지 않는다. |
| 한도 | 혜택과 같은 상품의 그룹 버전을 연결한다. 실효 기간·중첩을 검사한다. 구간은 하한 포함·상한 미포함. 구간 겹침·매칭 없음·기본 한도와 구간의 우선순위 불명은 null로 유지한다. |
| 월 사용량 | 신규 `MonthlyLimitUsageRepository`의 JPA 조회. 카드·그룹 버전·거래 한국 월로 조회하고 변환 시 상품도 대조한다. 정상 조회에서 그 달 행이 없으면 아직 혜택을 쓰지 않은 사용량 0을 전달한다(S15P31E103-324). 행은 첫 혜택 사용 승인 때 결제 원장이 만든다. 같은 카드·그룹인데 상품이 다른 행은 자료 불일치라 0으로 바꾸지 않고 미확인(`LIMIT_USAGE_MISSING`)으로 둔다. 조회 실패·시간 초과는 기존 예외 처리로 전달하며 0으로 대체하지 않는다. |
| 일 사용량 | `PaymentLimitUsageRepository`의 native 집계 한 쿼리. 이 기준 브랜치에 결제 엔티티가 없어 중복 매핑 대신 거래·소유 카드와 조인한다. 사용자·카드·상품·그룹·일·월을 맞추고 `restored_at IS NULL`인 미복구분만 합산한다. 정상 조회에서 해당 카드·그룹의 기록이 없으면 일 사용량 0을 전달한다. 미사용과 전부 취소·복구된 경우는 구분하지 않는다. 조회 실패·시간 초과는 기존 예외 처리로 전달하며 0으로 대체하지 않는다. 전월 실적 행 누락의 미확인 처리는 유지한다. |
| 공유 한도 | 한 그룹을 한 번 구성해 여러 혜택이 같은 ID를 참조한다. 월 집계와 결제별 상세 기록은 합산하지 않는다. |
| 합산 전 원시값 | 기존 실제 혜택 결과·한도 사용량은 원시 혜택 합계가 아니다. 집계 범위·원시값이 없어 null과 RAW_AGGREGATION_SCOPE_AND_VALUE_UNKNOWN으로 보존한다. |

조회 쿼리 수는 카드마다 증가하지 않고 상품·혜택·그룹 ID 집합별 일괄 조회로 제한한다. 기존 실적 Assembler는 서로 다른 인정월을 묶지 않고 월별로 나눠 조회한다. 새 엔티티는 기존 테이블을 읽는 `@Immutable` 매핑이고 Repository에 저장 메서드는 두지 않았다. 스키마·마이그레이션 변경은 없다.

POL-012·013의 2026-11-02 확정에 따라 거래 추가 비용은 카드·결제 방식 때문에 원금 외에 부담하는 금융 수수료·이자다. 현재 지원하는 일시불·YAMU 모의 무이자 할부에는 입력 구성 계층이 `BenefitPolicy.additionalCost = BigDecimal.ZERO`를 명시적으로 전달한다. 원금에 포함된 배송비·세금과 기존 연회비를 중복 차감하지 않는다. 별도 비용 자료가 없다는 이유로 ADDITIONAL_COST_UNKNOWN을 만들지 않는다. 필드는 향후 확장을 위해 유지하며, 엔진의 null 미확인 검사는 그대로다.

비용 0원만으로 complete=true를 설정하지 않는다. 승인된 모의 상품 범위에서만 계산 기준액=거래 전액·LIMIT_THEN_ROUND 및 확인된 단일 혜택 COUNT 사용 규칙을 전달한다. 관계가 없는 중첩 혜택·필수 조건·월 집계 누락 등은 여전히 카드별 미확인으로 반환한다. 실적 인정의 남은 미확인은 위 상품별 표와 구분한다. 기존 엔진의 LIMIT_THEN_ROUND 의미를 바꾸거나 모든 혜택의 동시 적용을 가정하지 않는다.

## 조회 실패와 읽기 범위

결제 가능 상태 준비의 미납 조회 실패는 그 조회에 의존하는 신용카드만 미확인으로 남기며 체크카드 판정은 계속합니다. 잔액 조회는 계좌별 부분 결과를 보존합니다. POS 결제 행 생성 전 준비 실패에는 아직 결제 ID가 없을 수 있습니다.

Service는 카드·상품·정책/사용량 조회, 혜택용 전월 실적 일괄 조회, 동률 보조 집계를 각각 독립된 readOnly `REQUIRES_NEW` 트랜잭션에서 수행한다. 전월 실적 조회가 불필요하면 해당 트랜잭션은 열지 않는다. 카드·정책 조회 트랜잭션을 정상 종료한 뒤 전월 실적을 읽고, 실적 트랜잭션이 종료된 밖에서 정책을 조립한다. 조회 예외 및 트랜잭션 종료 예외는 `execute` 밖에서 처리하므로 실패한 트랜잭션 안에서 조립·추가 조회를 계속하지 않는다. 분리된 읽기는 하나의 동일 DB 스냅샷을 보증하지 않으며 호출 측이 아직 커밋하지 않은 값도 보이지 않는다. 이 읽기는 승인 전 재검증·예약·동시성 보장이 아니다.

- `TransientDataAccessException`: 읽기 트랜잭션 롤백 후 DATABASE_READ_RETRYABLE·retryable=true로 구분한다. 후보 조회 자체가 실패하면 빈 카드로 NO_ELIGIBLE_CARD를 만들지 않고 `RecommendationReadException`을 던진다.
- 전월 실적 조회/트랜잭션 종료의 일시적 실패: 혜택의 전월 최소 실적 또는 연결된 실적별 한도 구간 때문에 이 조회에 의존하는 카드에만 DATABASE_READ_RETRYABLE을 붙인다. 이 카드의 정책은 조립하지 않아 미비교로 남기며, 실적을 0원이나 정상 집계 행 누락으로 바꾸지 않는다. 조회가 필요 없는 카드는 기존 정책·사용량 자료로 계속 조립한다. 다른 필수 정책까지 확인된 정상 후보가 있으면 PARTIAL_READY, 계산 가능한 후보가 없으면 DATA_INSUFFICIENT다. 실패 시에도 실제 조립기의 complete=false를 true로 변경하지 않는다. 같은 사용자/거래 전월의 의존 카드 ID를 한 번만 일괄 조회하고 자동 재시도하지 않는다.
- 그 밖의 DB 오류: 정상적인 정책 미등록으로 바꾸지 않고 예외를 전달한다. 예외의 비밀 접속 정보는 내부 결과에 넣지 않는다.
- 정책 미등록·집계 행 누락: 별도 사유, retryable=false. 같은 조회를 자동 반복하지 않는다.
- 동률용 누적액 읽기의 일시적 실패: RECOGNITION_AGGREGATE_READ_RETRYABLE로 구분하고 before만 미확인으로 남긴다. 기존 정상 혜택 정책을 지우지 않으며 다른 핵심 정책이 확인된 동률에서는 실적 기준만 생략한다.
- 자동 재조회·네트워크 호출은 추가하지 않았다. 공급 실패 후 일부 단계만 다시 조회하는 복구도 후속이다.

2026-11-02 전월 실적 실패 격리 변경 검증: `RecommendationServiceTest` 28개, `RecommendationInputAssemblerTest` 8개, `RecommendationSelectorTest` 38개로 **단위 테스트 74개 통과, 실패·오류·생략 0**. 실제 Service → Assembler → Selector 입력을 관찰해 독립 카드 정책/추가 비용 0원/complete=false 보존, 의존 카드 정책 미조립, 해당 카드에만 재시도 사유 연결을 확인했다. 전월 실적 조회의 예외와 정상 조회 후 트랜잭션 종료 예외를 각각 주입하고, 종료/롤백 호출 뒤에 독립 카드 조립이 수행되는 순서를 확인했다. 실적별 한도 구간만 있는 의존 카드도 포함했다. 모든 후보가 조회 의존 카드일 때 조립하지 않는 기존 테스트는 여전히 타당하므로 유지하되 DATA_INSUFFICIENT와 실패 카드 ID를 보강했다. 혼합 후보까지 조립을 금지하는 기대값으로 사용하지 않는다.

최종 PARTIAL_READY/READY·선택 카드 검증은 확인 완료 정책 fixture를 사용하는 별도 테스트다. 실제 조립기의 반환값을 complete=true로 바꾸지 않았다. 기존 성공 조회의 0원/집계 행 누락 구분, 동률용 조회 실패 시 혜택 정책 보존 및 동일 실적 입력의 보조 기준 생략도 검증했다. 실행 명령은 `./gradlew.bat test --tests 'com.yamu.be.recommendation.RecommendationServiceTest' --tests 'com.yamu.be.recommendation.RecommendationInputAssemblerTest' --tests 'com.yamu.be.recommendation.RecommendationSelectorTest'`다. 최초 실행은 재시도 사유 외의 정상적인 정책 미확인 사유까지 없어야 한다는 테스트 기대값 때문에 1개 실패했고, 재시도 사유의 영향 범위를 검사하도록 수정 후 전부 통과했다. DB와 트랜잭션 매니저는 mock이다. 실제 DB 장애·DB 통합·전체 BE 테스트는 이번 변경에서 실행하지 않았으며 실제 상품 추천 완성을 의미하지 않는다.

### 조회 실패 로그와 호출자 안내

2026-11-02 로깅·재시도 안내 변경 검증: `RecommendationServiceTest` 23개, `RecommendationPolicyLoaderTest` 3개, `RecommendationInputAssemblerTest` 8개, `RecommendationSelectorTest` 38개로 **단위 테스트 72개 통과, 실패·오류·생략 0**. 실제 WARN 이벤트와 JSON 직렬화를 검사했으며 DB 예외 메시지·원인에 넣은 민감한 내용이 노출되지 않음을 확인했다. 후보/상품/정책·사용량/전월·동률 실적 조회 실패, 읽기 트랜잭션 종료 실패, 정책 미등록·잘못된 정책의 구분, 정상 추천·미확인 처리를 검증했다. Repository와 트랜잭션 매니저는 mock이며 실제 DB 장애·DB 통합 테스트·HTTP/앱·전체 BE 테스트는 이번 변경에서 실행하지 않았다.

`RecommendationService`는 잡아서 결과로 바꾸는 일시적 DB 예외도 WARN으로 기록한다. 로그 필드는 paymentId, stage, scope, cardIds, code, exceptionType, retryable이다. stage는 후보 목록(`CANDIDATE_BATCH`), 상품(`PRODUCT_BATCH`), 정책·사용량 묶음(`POLICY_AND_USAGE_BATCH`), 전월 실적(`PREVIOUS_PERFORMANCE_BATCH`), 동률 실적(`TIE_PERFORMANCE_BATCH`)을 구분한다. 정책·사용량 단계는 Loader 전체의 조회 범위이며 그 안의 특정 SQL 실패를 식별하는 것은 아니다. 트랜잭션 시작 실패는 진입 예정 단계, 조회 후 트랜잭션 종료 실패는 `INPUT_READ_TRANSACTION_COMPLETION`, `PREVIOUS_PERFORMANCE_TRANSACTION_COMPLETION` 또는 `TIE_READ_TRANSACTION_COMPLETION`으로 기록한다.

예: `recommendation_db_read_failed paymentId=123 stage=PRODUCT_BATCH scope=BATCH_POTENTIALLY_AFFECTED cardIds=[1, 2, 3] code=DATABASE_READ_RETRYABLE exceptionType=org.springframework.dao.QueryTimeoutException retryable=true`. 카드 ID 목록은 확인된 일괄 조회 영향 가능 범위이며 각 카드의 개별 실패를 단정하지 않는다. 후보 조회 전 실패는 `scope=UNKNOWN_CANDIDATES cardIds=[]`다. 원본 예외·메시지·원인·스택·SQL·바인딩값·거래/카드 객체는 이 로그에 넣지 않는다.

호출자는 `Result.issues()`의 `code`, `retryable`, `message()`를 읽는다. DATABASE_READ_RETRYABLE 및 RECOGNITION_AGGREGATE_READ_RETRYABLE의 안내는 **“일시적으로 카드 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.”**다. `message`는 JSON 직렬화에도 포함된다. 정책 미등록·잘못된 정책·집계 행 누락은 기존 코드와 retryable=false를 유지하며 이 재시도 안내를 붙이지 않는다. 후보 조회 실패 예외는 `RecommendationReadException.code()`, `retryable()`, `paymentId()`, `getMessage()`로 같은 정보를 제공하고 원본 DB 예외를 원인으로 보존하지 않는다. 결과와 예외 모두 자동 재조회를 수행하지 않는다.

현재 `CardRecommender`에는 실제 `DatabaseCardRecommender` 빈을 추가했으며 기존 조건부 Mock을 대체한다. 기존 POS와 앱 조회 호출 흐름이 있으며 추천 재생성과 상태 조회를 연결한다. 결제 담당은 결과의 조회 오류와 후보 조회 예외를 모두 받아 기존 API의 `ApiResponse.error.code/message/details` 계약에 맞춰 변환해야 한다. 후보 조회 예외를 전역 처리기에 그대로 넘기면 현재는 일반 INTERNAL_ERROR 응답이 되어 재시도 안내가 전달되지 않는다. 기존 `GlobalExceptionHandler`는 BusinessException을 정해진 ErrorCode로 변환하고 기타 예외를 INTERNAL_ERROR로 처리한다. 이번에는 명세에 없는 HTTP ErrorCode나 새 핸들러/API를 추가하지 않고 내부 전달 정보를 제공했다.

## 담당 경계와 후속 작업

추천은 예상값만 계산한다. 실제 실적·혜택 사용량·잔액·신용한도를 변경하지 않는다. 결제 담당은 승인 전 재검증·동시성·중복 승인 방지·실적 및 한도 반영·청구·취소·복구를 구현한다. 결과 저장·조회·응답 변환의 코드 소유권은 기존 구현과 담당자 협의에 맞춘다.

| 남은 작업 | 연결할 내용 |
|---|---|
| 실제 입력 보완 | 상품별 표의 구체적인 미확인 및 결제 측의 확인된 거래 사실 공급. 결제 가능 상태는 추천 서비스가 조회. 이미 연결한 실적 원본·산출·월 조회를 전체 미구현으로 간주하지 않음 |
| 상품 정책 확인 | 승인된 03 Seed 이외의 중첩 관계, 미확인 계산·환산·반올림·기간·횟수 정책의 근거 확인 |
| 제한적 재조회 | 공급·서비스 계층에서 복구 가능한 읽기 실패만 제한적으로 재조회하고, 이후에도 미확인인 카드와 사유를 전달 |
| 결제·앱 연결 | POS 처리 뒤 엔진 호출, 추천 결과 JSON 저장과 앱 조회 응답 변환, PARTIAL_READY 표시·미비교 사유 전달 |
| 실제 상품 검증 | 지원 상품의 확인된 정책·실제 입력으로 계산 결과 검증 |

계산기에 적용 그룹·우선순위·fallback을 DB에서 공급하는 구조를 구현했고, 승인된 노리2·카카오뱅크·현대 M 관계를 03 Seed로 제공한다. 명시하지 않은 상품의 중첩 관계는 여전히 확인해야 한다. 테스트에서 만든 조합은 상품 약관이 아니다. 한도 사용 예상값도 전달된 정책에 대한 계산 결과이며 실제 차감 기록이 아니다. 지원 상품의 한도 차감 기준을 확인해 입력으로 표현해야 한다.

재조회는 엔진 안에 넣지 않는다. 기술 권장안은 읽기 최초 1회 + 추가 최대 1회, 전체 10초 이내 공통 기한이다. 기존 잔액 조회도 최대 10초이므로 호출을 단순히 두 번 반복하지 말고 남은 기한을 하위 호출에 전달해야 한다. 정책 미등록·인증/소유권 오류는 재시도하지 않고 웹·LLM으로 누락 약관을 추정하지 않는다. 이 재조회 연결은 미구현이다.

비교 제외 카드에는 결제 가능 여부 또는 혜택 정보 중 무엇을 확인하지 못했는지 안내한다. 실제 문의처가 제공되지 않으므로 관리자 문의나 문의 버튼을 요구하지 않는다. 일시적 조회 실패에는 기존 재시도 안내를 유지한다.

## 검증과 한계

2026-11-02 동률용 실적 공급 보완 후 관련 테스트(`com.yamu.be.recommendation.*`, `com.yamu.be.card.RecommendationPerformanceAssembler*`)는 **154개 통과·DB 10개 생략**, 실패·오류 0이다. 마지막 전체 BE 실행도 **845개 중 480개 통과·365개 생략**, 실패·오류 0이었다. 실제 MySQL 실행은 Docker 엔진 연결 불가로 검증하지 못했다.

새 검증은 원본 정책 파일을 읽는 실제 Resolver, 실제 PerformanceAssembler, 실제 Service와 Selector를 사용한다. Repository 응답만 준비한 단위 테스트에서 인정월·최소 기준·인정액·누적액이 Selector 입력까지 전달됨을 확인했다. 할인/무이자 제외와 원본의 미확인 범주, 신규 유예, 물리/모바일 교통 귀속 차이, 다른 카드·월의 자료 대조, 누적액 읽기 실패도 포함한다. Assembler/Resolver의 반환값을 준비된 실적으로 대체하지 않는다.

정확 동률의 신규 충족 경계는 위 서비스에서 얻은 PerformanceProgress 객체를 그대로 유지하고 **혜택 정책만 별도의 명시적 테스트 입력**으로 구성해 기존 Selector에 전달했다. `299,999+1`, 미달 유지, 이미 충족, 확인된 인정액 0, 미확인 null을 구분했다. 이것은 실제 실적 공급값의 선택기 반영 검증이며, 현재 DB 상품의 허용 조합/완전성까지 확인되어 서비스가 READY로 추천했다는 검증은 아니다. 실제 서비스의 현행 DB 정책 경로는 여전히 POLICY_INCOMPLETE로 미비교될 수 있다. 추가한 실제 DB 서비스 테스트는 상품 원본→실제 월 집계 SQL→Selector 입력 및 추천 전후 값 불변을 검사하도록 작성했지만 이번에는 생략됐다. 아래는 이전 작업 시점의 실행 기록이다.

2026-11-02 일 사용량 수정 후 `RecommendationInputAssemblerTest`, `RecommendationPolicyLoaderTest`, `RecommendationServiceTest`, `RecommendationDatabaseIntegrationTest`를 실행했다. 단위 테스트 **23개 통과**, 실패·오류·생략 0이다. 정상 일별 빈 결과가 `BenefitPolicy.Limit.used=0`으로 전달되는 것, 카드·상품·그룹 대조, 조회 시간 초과의 예외 전달, 월간 집계/전월 실적 누락의 미확인 유지를 확인했다. 실제 MySQL 통합 테스트 **7개는 Docker 엔진 연결 불가로 모두 생략**됐다. 미사용·전부 복구·혼합 기록 및 사용자·카드·그룹·날짜 필터는 실제 SQL을 사용하는 테스트로 추가·보완했지만 이번 실행에서 DB 통과를 확인한 것은 아니다. `restored_at IS NULL` 집계 SQL은 변경하지 않았다.

2026-11-02 추가 비용 정책 반영 후에는 `RecommendationInputAssemblerTest`, `RecommendationServiceTest`, `CardBenefitCalculatorTest`만 실행했다. **43개 통과, 실패·오류·생략 0**이다. 일시불·모의 무이자 할부를 각각 실제 Service → Assembler → BenefitPolicy → Selector/계산기 경로로 실행하고 추가 비용 0원과 다른 정책 미확인 유지를 확인했다. Assembler의 실제 반환 객체를 관찰하며 반환값을 준비된 정책으로 대체하지 않았다. DB 조회는 mock한 단위 테스트이고 이번 수정에서 실제 DB 테스트·전체 BE 테스트는 실행하지 않았다. 아래는 그 이전 DB 연결 작업의 검증 기록이다.

2026-11-02 S15P31E103-223에서 다음 명령과 `./gradlew.bat test`를 실행하고 결과 XML의 통과·생략 수를 확인했다.

```powershell
# be 폴더
.\gradlew.bat test --tests "com.yamu.be.recommendation.*" --tests "com.yamu.be.card.RecommendationPerformanceAssembler*"
```

| 검증 | 결과 |
|---|---|
| 기존 엔진·실적 조합 및 새 연결 단위 테스트 | 133개 통과, 실패·오류·생략 0 |
| 실제 DB 연동 | 새 조회/서비스 통합 5개 + 기존 월 지정 실적 2개 모두 생략. Docker 엔진 연결 불가로 실제 SQL·DDL 매핑·DB 값 불변을 이번 실행에서 검증하지 못함 |
| 전체 BE 회귀 | 821개 중 459개 통과·362개 생략, 실패·오류 0 |

추가 검증은 단일 카드, 기본 카드 단독 최대, 저장 순서와 미지정 순서 처리, 카드별 기준액 오류, 기간 미확인·제한 없음·일반 종료일 경계를 포함한다. 기본 카드 동률·완전 동률·신규 충족 여러 장 경로는 각각 5장 카드의 120가지 입력 순열에서 결과가 같음을 확인했다. 1원 차이와 최소 실적 경계는 기존 테스트를 유지했고 숫자만 바꾼 중복 테스트는 추가하지 않았다.

기존 엔진의 입력 순서·동시 재호출·최대 순혜택·동률·PARTIAL_READY·0원·한국 시각·공유 한도·조합 검증을 유지했다. 새 단위 테스트 18개는 Repository 호출의 일괄 구성, 저장 필드 변환, 전월 조회 호출 인자, 상품 누락, 타인 카드/판정 차단, 읽기 실패, 카드별 잘못된 정책 격리, 실제 Selector까지의 연결을 검증한다. 서비스의 READY/PARTIAL_READY/0원 단위 테스트에서 확인된 정책을 제공하는 것은 테스트용 stub이며 운영 상품 정책이 아니다.

새 MySQL 통합 테스트는 실제 Repository의 기간/상품 필터, 같은 카드의 여러 월·0/행 부재, 공유 한도·구간·복구/타인 일 사용량 필터와 추천 전후 DB 값 불변을 검증하도록 추가했다. 이번에는 Docker가 없어 모두 생략됐으므로 이 항목들이 실제 DB에서 통과했다고 주장하지 않는다. 애플리케이션 컨텍스트와 JPQL 등록은 전체 테스트에서 확인했지만 MySQL 실행·스키마 validate의 대체 검증은 아니다. 신규 엔티티는 기존 테이블을 매핑하며 DB 스키마·마이그레이션 변경은 없다. 자동 재조회·상품 확인 근거/조합/인정 규칙 공급·실제 결제 호출·결과 저장·앱 응답·승인 흐름은 검증/구현 범위 밖 또는 위 후속 항목이다.

정책 근거: [POL-011](https://app.notion.com/p/ed231720f35882eca46c01ea0aed9467), [POL-012](https://app.notion.com/p/27531720f35882b39cf2810a7ed9fa02), [POL-013](https://app.notion.com/p/1cd31720f358822a8351010f7c6ad0c0), [카드 추천 공통 처리](https://app.notion.com/p/0ac31720f358820387d60131aa446a09).

## 실적·한도·소수점 경계 DB 통합 검증 (S15P31E103-396, FSP-10)

2026-11-10 최신 원격 develop `47219b85e940a423565290a92752a3b71a937f6a`에서 `test/be/S15P31E103-396-recommendation-boundary-integration`을 생성했다. 실행 소스는 해당 커밋에 아래 테스트 변경을 더한 미커밋 작업 트리다. 테스트 파일의 Git blob 해시는 `d1cbd8549808b98b448e95fc5299fa98267b51a3`이다.

변경 파일은 `SimulationSeedRecommendationDatabaseTest.java`와 이 문서다. 기존 Seed fixture와 실제 추천 진입점을 재사용해 테스트 중복을 줄였다. `RecommendationDatabaseIntegrationTest`는 수정 없이 기존 8건을 함께 실행했다. 운영 코드·Seed SQL·스키마·forecast.md는 변경하지 않았다.

### 확인한 경계와 결과

모든 새 사례는 Testcontainers MySQL의 실제 Repository → 정책 Loader/Assembler → Selector/계산기 → `CardRecommender` 결과를 통과한다. 엔진 반환값이나 정책 조립 결과를 mock하지 않는다.

| 구분 | DB·거래 조건 | 확인한 결과 |
|---|---|---|
| 전월 실적 3건 | Mr.Life 전월 299,999 / 300,000 / 300,001원, GS25 구매 10,000원 | 편의점 혜택과 카드 순혜택 각각 0 / 1,000 / 1,000원. 실적 미달 혜택도 기존 응답 계약에 따라 0원 행으로 남음 |
| 거래 기준 월 | 거래 UTC 2026-10-31 15:30 = 한국 11월 1일 00:30, 당월 실적은 999,999원 | 10월 실적으로 혜택 판정. 당월 값이나 이번 구매액을 전월 실적에 더하지 않음 |
| 노리2 일부 잔여 | 통합 한도 20,000원 중 17,000원 사용, 앱스토어 유튜브 구독 15,000원 | 혜택·예상 사용 2,500원, 계산 전 잔여 3,000원, 계산상 잔여 500원 |
| 노리2 정확 소진 | 같은 거래, 18,200원 사용 | 혜택·예상 사용 1,800원, 계산 전 잔여 1,800원, 계산상 잔여 0원 |
| 노리2 이미 소진 | 같은 거래, 20,000원 사용 | 혜택·예상 사용·잔여 모두 0원 |
| 실제 현대 M Seed HALF_UP | 우대 한도 9,000포인트 사용. 구매 100,099 / 100,100 / 100,101원 | 우대 1,000포인트 + 초과 원금 기본 적립 원값 1,201.485 / 1,201.500 / 1,201.515 → 기본 적립 1,201 / 1,202 / 1,202포인트. 기존 구매 100,000원 사례도 유지 |
| 합성 반올림 경계 12건 | DB에 HALF_UP/DOWN, 1원/10원 단위, PER_TRANSACTION 저장. 10% 혜택의 경계 직전·경계·직후 | HALF_UP 단위 1: 원값 10.4/10.5/10.6 → 10/11/11. 단위 10: 104.9/105/105.1 → 100/110/110. DOWN 단위 1: 10.9/11/11.1 → 10/11/11. 단위 10: 109.9/110/110.1 → 100/110/110 |
| 한도 적용 후 절사 | 합성 DOWN·10원 단위, 월 잔여 106원, 원값 119.9원 | 119.9 → 한도 106 → 절사 100원. 예상 사용 100원. 반대 순서의 106원과 구분 |

새 사례와 확장한 현대 M 사례에서 추천 전후 `card_monthly_performance`, `monthly_limit_usage`, 해당 카드의 `payment_transaction` 조회 결과가 동일함을 확인했다. 예상 사용량은 응답 값이며 DB에 차감하지 않는다. 합성 반올림 설정은 테스트 격리 DB에서만 변경하고 `finally`로 원래 값을 복원한다.

### 실행 기록

```powershell
# be 폴더
.\gradlew.bat test --tests '*SimulationSeedRecommendationDatabaseTest' --tests '*RecommendationDatabaseIntegrationTest'
```

최종 실행은 2026-11-10 KST, MySQL 컨테이너 생성 10:35:48, Seed 테스트 시작 10:36:34, 기존 DB 통합 테스트 시작 10:36:56이며 전체 Gradle 실행은 1분 28초다. XML의 UTC timestamp는 각각 `2026-11-10T01:36:34.486Z`, `2026-11-10T01:36:56.482Z`다.

| 실행 대상 | 성공 | 실패·오류 | 생략 |
|---|---:|---:|---:|
| SimulationSeedRecommendationDatabaseTest | 36 | 0 | 0 |
| RecommendationDatabaseIntegrationTest | 8 | 0 | 0 |
| 합계 | 44 | 0 | 0 |

`mysql:8.4.11` 컨테이너 기동과 격리 DB `test_1`, `test_2`에 대한 Flyway 적용 로그를 확인했다. Gradle은 BUILD SUCCESSFUL이며 테스트 소스 컴파일과 `git diff --check`도 통과했다. 결과 XML과 HTML 보고서는 `be/build/test-results/test/`, `be/build/reports/tests/test/`에 생성된다(재실행 시 덮어씀).

첫 실행은 41건 중 40건 성공·1건 실패·생략 0이었다. 새 실적 미달 검사가 혜택 행이 없을 것으로 잘못 기대했지만 실제 계약은 0원 행을 반환했다. 해당 assertion을 바로잡고 현대 M 소수점 사례 3건을 추가한 뒤 위 44건을 재실행했다. 운영 계산 코드는 수정하지 않았다.

### 검증 범위와 한계

- 금융망 `SsafyAccountApi`는 mock이며 잔액을 명시적으로 공급한다. 기존 회귀 사례의 금융망 실패·신용한도·청구 조회 실패 주입은 해당 사례에 한정된다. 새 경계 검사에서 Repository·Assembler·계산기·Selector의 반환값은 stub하지 않는다. Redis도 기존 테스트의 Testcontainers를 사용한다.
- 실제 Seed의 실적·한도·현대 M 반올림 설정을 재사용했지만 고객 실적·사용량은 가상 fixture다. 현대 M의 포인트 원화 환산은 기존 승인된 모의 기준 1포인트=1원이며 실제 현금 가치 검증이 아니다.
- 합성 HALF_UP/DOWN·10원 단위는 전달과 계산 검증용이다. Mr.Life의 실제 약관이라고 주장하지 않으며 Seed 원본이나 상품 정책을 변경하지 않는다.
- **DB에는 한도 적용과 반올림의 선후 순서를 저장하는 컬럼이 없다.** `rounding_stage`는 거래별/합산 후 처리 단계다. 이번 검사는 DB 방식·단위·PER_TRANSACTION과 조립기가 승인된 모의 정책으로 제공하는 `LIMIT_THEN_ROUND`의 결과를 검증했다. DB에서 선후 순서를 조회·전달하는 기능이나 `ROUND_THEN_LIMIT`의 DB 통합 지원을 검증한 것은 아니다. 이를 지원하려면 별도 정책·저장 계약이 필요하다.
- 기존 POS 서비스 호출·승인 시 추천 스냅샷 재생성 검사도 같은 실행에서 통과했다. 이는 격리 DB의 서비스 통합 회귀이며 운영 DB, 실제 금융망, HTTP 요청, 앱 화면, 전체 BE 테스트 통과를 뜻하지 않는다. 승인·취소·원장 기능은 추가하지 않았다.

## 추천 후보별 안내 문구 (S15P31E103-440)

`candidates[].reason`은 후보 카드별로 다음 규칙을 따른다.

- 선택 카드(1순위): 기존 선택 설명 전체를 유지한다. 동률 판단·부분 추천 설명도 포함한다.
- 나머지 후보: 해당 카드의 `netBenefit`으로 “예상 순혜택은 N원입니다.”를 반환한다. 소수 금액은 보존하고 불필요한 소수점 이하 0은 제거한다(예: 1.0 → 1원, 0.5 → 0.5원).

`DatabaseCardRecommender`에서 구성하여 `PaymentRequestViewReader`를 통해 전달한다. Android의 2순위 이하 `reason` 제거 임시 처리(S15P31E103-411)와 화면 표시 반영 여부는 후속 작업에서 결정하고, 반영 후 후보별 문구 노출을 재검증한다.

## 비교 제외 안내 문구 (S15P31E103-388, B-04 일부)

실제 문의처가 제공되지 않으므로 비교 제외 안내에 관리자 문의를 요구하지 않는다. 결제 가능 여부를 확인하지 못한 경우와 혜택 정보를 확인하지 못한 경우를 구분하며, 카드가 없다는 의미로 바꾸지 않는다.

- 결제 가능 여부 미확인: “결제 가능 여부를 확인하지 못해 비교에서 제외했어요.”
- 혜택 정보 미확인: “혜택 정보를 확인하지 못해 비교에서 제외했어요.”

`RecommendationSelector.CardEvaluation.message()` → `DatabaseCardRecommender` → `PaymentRequestViewReader`를 거쳐 `uncomparedCards[].message`에 전달한다. 일시적 조회 실패의 기존 재시도 안내는 우선 전달한다. 계산·선택·상태·오류 코드·직접 결제 허용 판정은 변경하지 않는다.

2026-11-10 사용자 확정에 따라 [POL-011](https://app.notion.com/p/ed231720f35882eca46c01ea0aed9467)과 [카드 추천 공통 처리](https://app.notion.com/p/0ac31720f358820387d60131aa446a09)의 요약·본문·안내 예시에 남아 있던 관리자 문의 및 문의 버튼 요구를 수정했다. POL-011의 검증 기준과 공통 처리의 연결 항목도 같은 기준으로 맞췄다. 두 미확인 사유를 구분하고 일시적 조회 실패의 재시도 안내를 유지하는 것이 확정된 기준이며, 원본 정책 문서의 문의 요구 정리는 완료했다. 문의 시스템 신설이나 B-07 계산 변경은 포함하지 않는다.
