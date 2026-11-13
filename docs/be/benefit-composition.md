# 구매 항목·대상 경로·혜택 조합 연결

2026-11-03 승인 설계 구현. 현재 브랜치의 코드 계약이며 운영 DB 적용/실제 결제 완료를 의미하지 않는다.

## 조회와 계산

`DatabaseCardRecommender` → `RecommendationService` → 기존 카드/상품 Repository → `RecommendationPolicyLoader` → `RecommendationInputAssembler` → 기존 Calculator/Selector를 사용한다. 실제 포트가 등록되면 기존 조건부 Mock 빈은 등록하지 않는다. POS 접수와 승인 중 추천 재생성은 실제 포트를 호출한다. 카드별 결제 가능 상태는 추천 서비스가 조회하며 외부 잔액 조회는 POS 저장 트랜잭션 전에 준비한다.

구매 항목은 merchantId와 별개인 purchaseItemCode다. 신규 POS 선택은 `PurchaseItemReader.validateNewSelection(code)`로 등록·활성을 확인하고 최초 접수 트랜잭션에서 보존한다. 미지정은 NULL이며 다른 상품 구매로 간주하지 않는다. 보존 거래의 추천/재시도는 `validateStored`를 사용해 비활성화 후에도 원래 코드를 재현한다. 사용된 코드의 삭제/의미 변경/재사용은 금지하며 비활성화로 신규 선택을 제한한다. 상품 카테고리/가맹점 허용 목록을 대신 만들지 않는다.

대상 그룹과 멤버, 적용 그룹과 멤버, 정책 구매 항목 등록값을 ID 집합으로 일괄 조회한다. 대상 그룹은 같은 혜택만, 적용 그룹은 같은 상품의 특정 혜택 버전만 연결한다. 대상 그룹 내 AND/그룹 간 OR, 제외 경로, 기존 공통 CONDITION을 세 가지 상태로 판단한다. 빈 적용 경로를 항상 적용으로 바꾸지 않는다. 여러 경로가 맞아도 동일 혜택은 한 번만 계산한다.

새 `applicationGroups` 입력은 STACK/MAX_ONE/PRIORITY_ONE을 표현한다. 기존 `allowedCombinations` 생성자와 계산 경로는 유지하며 새 DB 입력은 적용 그룹을 사용한다. MAX_ONE은 같은 거래 전 사용량에서 독립 계산한 경제적 혜택을 비교한다. PRIORITY_ONE은 조건에 맞는 우선 항목을 선택하며 한도 소진만으로 다음 항목을 실행하지 않는다. 같은 우선순위에서 둘이 맞으면 미확인이다. 그룹 없는 혜택은 대상 불일치가 확인된 다른 혜택과 독립 처리하고, 실제 거래에서 중첩되는데 관계가 없으면 `BENEFIT_RELATIONSHIP_UNKNOWN`이다.

STACK은 개별 한도를 반영한 값과 공유 금액 한도를 구분한다. 동일 단위의 한 공유 금액 한도에 함께 걸리는 양수 혜택에서 합계가 잔여를 넘으면 총액은 잔여로 확정한다. 상세 value/krwValue는 NULL, `unallocatedBenefitGroups`에 혜택 ID·공유 한도 ID·총액·단위·SHARED_LIMIT_ALLOCATION_UNRESOLVED를 남긴다. 해당 묶음은 합계에 한 번만 넣는다. 배분을 알 수 없는 개별 한도/횟수의 사용 예상치는 제공하지 않으며 공유 금액 사용 예상치만 확정한다. 미배분만으로 complete/READY/PARTIAL_READY를 바꾸지 않는다.

다중 공유 금액 한도 네트워크, 서로 다른 적용 그룹 사이의 공유 한도 순서, 부족한 공유 횟수의 배분 등 이번 Seed/설계로 결정하지 못한 토폴로지는 각각 구체적 미확인 사유로 남긴다. LIMIT_THEN_ROUND를 바꾸지 않으며, 공유 한도가 실제로 충돌하는 이 순서의 배분을 임의 확정하지 않는다. 정액은 기존대로 잔여 한도까지 지급하고 신규 전액 미지급 설정은 없다.

현대 M의 우대 혜택은 금액 한도 연결이 정확히 하나인지 검사하고, 남은 포인트 한도/우대율로 반올림 전 원금을 분할한다. baseAmount 입력을 바꾸지 않고 해당 계산 호출에만 원금을 전달한다. 기본 조건·실적·반올림·한도를 다시 검사하며 기본을 전체 원금에 중복 실행하지 않는다. 우대와 기본 대상 원금이 달라 분할 범위를 확인할 수 없거나 비종결 소수로 임의 원금 반올림이 필요하면 별도 미확인 사유를 제공한다. 현재 Seed의 5% 분할은 이 소수 제한에 걸리지 않는다. 자기 참조·연쇄 전환은 거절한다.

## Seed와 근거

새 Flyway `V20261103_1`은 기존 대상을 경로로 이관하고 비CONDITION의 is_excluded를 FALSE로 정규화한다. 같은 대상이 적용/제외 양쪽에 있어도 두 경로를 남긴 뒤 대상 행을 통합한다. 경로 코드는 타입·브랜드 코드/대상 코드·적용/제외 의미로 만든 해시이며 자가 증가 ID를 사용하지 않는다. MySQL은 AUTO_INCREMENT 컬럼을 CHECK에서 참조할 수 없으므로 fallback 자기 참조는 INSERT/UPDATE 트리거로 차단한다. [MySQL 8.4 제약](https://dev.mysql.com/doc/refman/8.4/en/numeric-type-attributes.html).

Docker 활성화 후 실제 실행에서 기본 MySQL 바이너리 로그 설정의 트리거 생성 권한 오류(1419)를 확인했다. `MySqlTestSupport`의 폐기 가능한 컨테이너에만 `--log-bin-trust-function-creators=1`을 지정한다. 개발/운영 DB 설정은 변경하지 않았다. 실제 배포 전 DBA는 마이그레이션 계정의 TRIGGER 권한과 바이너리 로그 환경의 생성 권한 또는 해당 서버 설정을 준비해야 한다. 이 조건이 없으면 기본 설정에서 이 마이그레이션이 실패하며 MySQL DDL은 전체 롤백되지 않으므로 적용 전에 확인한다. 자동 clean/repair로 우회하지 않는다.

현재 신규 적재 순서는 Flyway → `01-card-catalog.sql` → `02-card-catalog-ab20.sql` → `03-benefit-composition.sql`이다. 01/02도 새 구조의 경로를 생성하므로 새 DB와 기존 DB 재실행을 모두 지원하도록 변경했다. 02는 기존 생성 결과를 `target-groups.mjs` 변환기로 갱신했으며 이후 원본 생성기도 같은 변환기를 사용한다. 03은 명시적 승인 관계를 적재하는 별도 반복 실행 가능한 SQL이다. 기존 로컬 Seed CLI는 01만 실행하는 기존 범위를 유지하므로 17종 적재에는 02/03을 명시적으로 실행해야 한다. 개발/운영 DB에는 적재하지 않았다. Testcontainers MySQL에는 01→02→03을 실제 적재해 아래 추천 경로를 검증한다.

- 노리2 모바일·구독 STACK, 앱스토어 AND YOUTUBE_PREMIUM_SUBSCRIPTION 경로는 **사용자 전달 카드사 전화 상담** 근거다. 공개 약관 직접 확인으로 표현하거나 다른 구독·결제 경로로 확대하지 않는다. 기존 직접 결제 경로는 OR로 보존한다. 기존 공통 제외 사실을 임의로 false로 만들지는 않는다.
- 카카오뱅크는 Seed에 있는 기본과 현재 추가 혜택만 STACK으로 연결한다. MAX_ONE 관계를 자동 생성하지 않는다.
- 현대 M은 두 우대 순위 1, 기본 순위 2와 한 단계 fallback을 적재한다. 우대의 국내/해외 공통 CONDITION을 보존한다. ‘한도 초과 시 기본 적립률’은 공식 문구, 같은 거래의 원금 분할은 승인된 설계 해석이며 공식 숫자 예시 확인으로 기록하지 않는다.

새 구조가 기존 미확인 숫자를 실제 상품 정책으로 확인한 것은 아니다. 아래 승인된 모의 기준으로만 계산 입력을 보완한다. Seed 범위 밖·필수 사실·미등록 관계·집계 누락의 미확인은 유지하며 17종 모든 거래의 READY를 보증하지 않는다.

## 승인된 모의 정책 — 2026-11-03 일괄 결정

의사결정 세션의 사용자 일괄 승인에 따른다. **공식 자료 / 사용자 전달 상담 / 승인된 원금 분할 해석 / 아래 YAMU 모의 규칙**을 구분한다. 원본 Seed의 NULL을 공식 값으로 덮어쓰지 않고 입력 조립 시 적용한다.

| 규칙 | 적용 값·범위 |
|---|---|
| `YAMU_BASIC_PURCHASE/1` | 미확인인 국내/해외는 국내, 시설은 일반 매장, 결제 경로는 직접 결제, 미지정 구매 항목은 명시한 특수 거래를 제외한 일반 구매, 자동납부/정기결제 및 별도 카드사 행사는 미적용. 확인된 값 우선. 온라인/오프라인·공식 경로·제휴 수단은 추정하지 않음 |
| `YAMU_SIM_CARD_POLICY/1` 범위 | `simulation-catalog.json`에 명시한 Seed 17종·56개 혜택의 버전 1. 승인된 등록 목록의 모의 완전성만 보증하며 실제 약관 전체를 보증하지 않음. 빠진 필수 대상·제외 사실·중첩 관계는 계산기가 계속 검사 |
| 누락 반올림 | `DOWN`, 지급 단위 1원/1P, 거래별 `PER_TRANSACTION`. 기존 HALF_UP 등 실제 저장값 우선 |
| 계산 입력 | 대상 조건을 충족한 일반 거래의 원금, 기존 승인 순서 `LIMIT_THEN_ROUND`. 현대 M은 fallback에서 해당 원금을 분할. 임의로 ROUND_THEN_LIMIT로 전환하지 않음 |
| 누락 유효기간 | 한국 시간 `[2026-11-01, 2026-12-01)`의 모의 창. 알려진 경계와 교집합을 취하고 이미 종료된 실제 정책은 부활시키지 않음. 알려진 양쪽 경계는 보존 |
| 누락 포인트 환산 | M ME4 3개·ZERO 포인트형 1개·taptap O 1개·트래블로그 1개에만 모의 비교용 `1P=1원`. 확인된 환산값 우선. 실제 환급액·현금 가치 보증 아님 |
| 단일 혜택 COUNT | 버전 1의 한 혜택에만 연결된 COUNT에 `PER_BENEFIT`, `consumeOnZero=false`. 새로운 공유 COUNT 배분이나 사용량 갱신을 만들지 않음 |

`simulationRules`에는 적용 규칙 ID/버전, 카드 ID·혜택 ID, 보완 필드와 다음 안내가 담긴다. JSON 저장/복원에서도 유지한다. 기본 거래 규칙을 적용하지 않았으면 그 규칙을 기록하지 않는다.

> YAMU에 등록된 혜택과 모의 계산 기준으로 추천했습니다. 포인트의 비교 금액은 실제 환급액과 다를 수 있습니다.

정책 비용은 지원 일시불·모의 무이자 할부 모두 명시적 0원이다. 비용 0원만으로 정책을 완료 처리하지 않는다. 실적·월 사용량·잔액·결제 가능성은 모의로 채우지 않는다. ZERO 해외 거래의 국내외겸용 여부처럼 필요한 정보가 없으면 DATA_INSUFFICIENT가 될 수 있다.

공유 한도의 `LIMIT_THEN_ROUND`는 그대로다. 노리2의 정수 원시 금액·거래별 DOWN/단위 1인 경우 한도 차감과 반올림을 어떤 개별 순서로 적용해도 총액이 동일함을 확인한 좁은 경우만 총액 1,800원을 제공한다. 그 밖의 충돌은 기존 미확인으로 남긴다. 상세 배분·개별 횟수 사용 예상값은 만들지 않는다.

## 2026-11-12 일반 모의 구매 보완 승인

사용자 승인에 따라 `YAMU_BASIC_PURCHASE/2`는 일반 모의 구매의 미확인 특수 거래 조건을 보완한다.
2026-11-13 리뷰 보완으로 교통·공과금·교육의 미지원 14개 업종은 입력을 차단하고 이 기본값을 공급하지 않는다.
공용 업종 사전은 유지하며 API의 `simulationSupported`로 고객 앱 선택 범위를 맞춘다. [입력 차단·재현 방법과 최종 검증](recommendation-audit-457.md)을 참고한다.
공과금·관리비·임대료·보험료·교육비·정부지원금·교통비·통행료·선불 구매·취소·비구매·특수 자동할부/캐시백 등
`BasicPurchaseSimulation.SPECIAL`에 명시한 거래 종류만 `false`로 채운다. 상품별 조건을 일괄 충족시키는 변경은 아니다.

- 구매 항목이 지정됐거나 `TRANSACTION_KIND:*` 중 하나라도 확인된 `true`이면 이 기본값을 공급하지 않는다.
- 확인된 자동납부·정기결제에도 공급하지 않으며, 기존 `true`/`false` 값은 보존한다.
- 카드 구분, 공식 구매 경로, 포인트 사용액과 `RULE:*`는 추정하지 않는다. 정책·실적·사용량의 미확인도 유지한다.
- 결과에는 규칙 버전 2와 실제 보완한 필드를 남긴다. 이미 저장된 추천 스냅샷을 다시 쓰거나 원본 Seed를 수정하지 않는다.
- 회귀 진입점은 `BasicPurchaseSimulationTest`와 `CatalogRecommendationMatrixTest.reportsCatalogCoverageAcrossOrdinaryPurchases`다.
  후자는 17종 카드, 5개 가맹점, 금액 1천/1만/10만원, 전월 실적 0/299,999/100만원의 765개 카드별 판정을
  한국 시간 2026-11-12 정오·일시불·일반 시설·구매 항목 미지정·혜택 사용량 0 조건으로 검사한다.
  결과는 `be/build/reports/recommendation-matrix.tsv`에 기록하며, 실제 고객의 실패율이나 앱 E2E 검증을 뜻하지 않는다.

수정 전후 같은 765개 조합을 비교한 결과, 미비교는 **255건(33.3%) → 102건(13.3%)**, 계산 가능은
**510건 → 663건**이었다. 미비교 153건이 해소됐고 기존 계산 가능 510건의 상태·혜택 금액은 모두 같았다.
카카오뱅크 프렌즈·트래블로그·LOCA LIKIT 2.0은 각각 45건, 현대 M은 15건, 청춘대로 톡톡은 3건이 해소됐다.
남은 ZERO 할인형 45건은 `RULE:OVERSEAS_ELIGIBILITY`, ZERO 포인트형 45건은 `CARD_FORM:GLOBAL`,
처음 12건은 포인트 사용액·구매 경로·배달앱 여부 등의 미확인이다. 이 값은 이번 승인 범위에서 추정하지 않았다.
추천·결제 추천·실적 조립기 회귀는 **299개 통과, 실패/오류/생략 0**이다(765개 조합은 그중 한 테스트에서 검사).

## 결제 담당 호출 계약

`RecommendationRequest`에 최초 보존한 purchaseItemCode, 검증한 verifiedFacts를 전달한다. availability는 호환용이며 서버 판정에 사용하지 않는다. 기존 MerchantContext는 결제처/업종/채널/시설 용도다. 누락된 카드 결제 가능 여부는 AVAILABLE이 아니라 UNKNOWN이다. DatabaseCardRecommender는 기존 잔액 서비스를 이용한 서버 판정과 계산을 연결하고 후보, 제외, 미비교 및 조회 사유를 반환한다. 자동 재시도는 추가하지 않는다.

`PosPaymentRequestService`는 거래 조건 전체를 전달해 저장 트랜잭션 전에 잔액, 정책, 사용량과 실적 조회를 완료합니다. 결제 저장과 바코드 전환 뒤에는 실제 결제 ID만 붙여 준비한 입력으로 계산합니다. 접수 추천은 조회 시점의 예상값이며 최종 승인 시 최신 상태 확인과 사용량 반영은 결제 담당의 책임입니다. `PaymentApprovalService`의 추천 재생성도 서버 상태를 조회합니다. `SKIPPED` 신용한도는 충분 확인과 구분하고 계좌별 잔액 실패는 해당 카드에만 연결합니다. 앱 응답은 미배분 개별 금액 `null`과 확정 그룹 총액을 함께 전달합니다.

`RecommendationResult.schemaVersion=1`, 후보별 netBenefit은 필수 숫자(BigDecimal), benefits[].amount는 정수 또는 NULL, unallocatedBenefitGroups는 필수 배열이다. 원시 금액은 BigDecimal로 보존해 정률 소수를 버리지 않는다. 포인트 환산 후 경제적 순혜택 소수도 정수로 절사하지 않는다. RuleVersion은 혜택별 실제 버전을 보존하고, 하나의 공통 버전이 없으면 단일 version은 NULL이다.

`instantDiscount()`는 확인된 즉시할인 상세와 전부 즉시할인인 미배분 묶음의 총액만 한 번씩 합산한다. 즉시/이후 반영이 섞여 즉시금액이 불명확하거나 할인 반영 시점이 없으면 0으로 대체하지 않고 거절한다. 실적 인정 Resolver도 NULL 상세를 할인 없음으로 오해하지 않는다.

```java
// 결제 담당의 기존 접수 트랜잭션: 소유권·불변 요청 비교·잠금은 호출자가 처리한다.
databaseRecommendationResultStore.preservePurchaseItem(userId, paymentId, validatedPurchaseItemCode);
RecommendationResult result = cardRecommender.recommend(preservedRequest);
databaseRecommendationResultStore.save(userId, paymentId, result);
// 위 트랜잭션 커밋 성공 이후에만 저장 완료로 응답한다.
// 앱 조회: 재계산하지 않는다. 과거 NULL이면 Optional.empty().
databaseRecommendationResultStore.find(userId, paymentId);
```

`DatabaseRecommendationResultStore`의 쓰기는 MANDATORY 트랜잭션이며 사용자·거래로 필터링한다. 구매 항목/추천 JSON 및 수정 시각/버전만 변경한다. 실적·사용량·승인 금액·상태를 갱신하지 않는다. 저장 실패는 예외이며 저장 완료 결과를 반환하지 않는다. 호출자는 동시 저장·재시도·중복 요청·승인 재검증을 담당하고, 미배분 결과를 payment_benefit의 승인 확정 내역으로 복사하면 안 된다. 기존 HTTP API/앱 화면은 새로 만들지 않았다.

## 검증 구분

순수 조건/계산 테스트는 확인된 가상 정책을 사용한다. 기존 조합 연결 테스트는 미확인 정책을 덮어쓰지 않고 그룹 전달을 검사한다. 추가 실제 Seed 테스트는 승인된 모의 정책을 실제 조립기로 적용하고 엔진의 양수 추천까지 검사한다. JSON·저장 호출 단위 테스트와 MySQL 마이그레이션/Seed/JPA/보존 테스트를 구분한다. Docker 엔진 연결 불가 시 DB 테스트는 생략되며 BUILD SUCCESSFUL만으로 실제 DB 제약/저장 검증 통과를 보고하지 않는다.

2026-11-03 Docker 활성화 전 기록: 관련 Gradle 테스트 **253개 중 211개 통과, 실패·오류 0, MySQL 테스트 42개 생략**. 새 조합/실제 조립/구매 항목/결제 포트→서비스→Selector 연결, JSON NULL/0 보존, Mock 대체 빈 구성을 포함한다. 이때의 MySQL 검증은 Docker 엔진 연결 불가로 생략됐으며 아래에 실제 실행 결과를 구분한다.

같은 날 Docker 활성화 후 위 관련 범위를 다시 실행해 **253개 전부 통과, 실패·오류·생략 0**을 확인했다. 포함된 실제 MySQL 8.4.11 테스트는 42개다. 기존 데이터의 적용/제외 경로 보존 이관, 17종 Seed 재실행, 복합 FK와 자기 참조 차단, 실제 JPA 조회·월/사용량 필터, 실적 공급과 값 불변, 사용자별 추천 JSON 저장·조회가 통과했다. 최초 실행은 트리거 생성 권한 오류 1419로 DB 컨텍스트가 실패했다. 테스트 전용 컨테이너 설정을 반영한 뒤, 저장 거절 예외를 Spring이 InvalidDataAccessApiUsageException으로 감싸는 실제 동작에 맞춰 테스트 기대 타입을 보완했다. 소유권 거절 로직을 완화하지 않았다.

이후 보존 테스트에 총액 1,800원·상세 NULL·확인된 0원 fixture를 추가해 `RecommendationSnapshotDatabaseTest`를 별도 재실행했고 **1개 통과, 실패·오류·생략 0**이다. 실제 MySQL JSON_TYPE/JSON_EXTRACT와 저장 객체 복원으로 NULL/0 구분을 검사했다. 이 fixture의 저장 검증은 실제 상품의 계산 정책 완전성 검증이 아니다. 개발/운영 DB에는 적용하지 않았고, 전체 BE·POS/앱 E2E·승인 동시성은 실행하지 않았다.

실행 명령(작업 디렉터리 be):

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.global.schema.CardCatalog*Test' --tests 'com.yamu.be.global.schema.BenefitCompositionMigrationTest' --tests 'com.yamu.be.global.schema.InitialSchemaMigrationTest' --tests 'com.yamu.be.card.RecommendationPerformanceAssembler*'
```

Seed 생성 회귀 `node --test be/seed/catalog-ab20/build-seed.test.mjs`는 **1개 통과**다. 원본 ZIP 없이 테스트용 입력으로 생성기와 동일 변환기를 실행한 검증이며, 원본 20종 재가공이나 실제 SQL 실행 검증이 아니다. 중간 실행에서 구형 JSON의 새 필드 NULL 처리 및 생성자 오버로드/미확인 사유 회귀를 수정했으며 위 수치는 수정 후 마지막 실행의 XML 기준이다. `git diff --check`도 통과했다.

예상 한도 사용량은 후보의 expectedLimitUses로 전달한다. 미배분 충돌의 개별 한도·횟수는 공급하지 않으며, 이는 승인 확정 기록이 아니다. 사용량·실적·잔액·신용한도 갱신, 승인·청구·취소·복구·멱등성·동시 저장 구현은 추가하지 않았다.

## 실제 Seed 추천 검증과 통합 회귀 — 추가 실행

`SimulationSeedRecommendationDatabaseTest`는 01/02/03 Seed를 Testcontainers MySQL 8.4.11에 실제 적재하고 `CardRecommender → Service → JPA/집계 조회 → 실제 Assembler → Selector/Calculator`를 실행한다. 조립기 반환값을 완성 정책 fixture로 바꾸지 않는다. 고객 보유 카드·실적·월 사용량·결제 가능 판정은 명시적인 가상 고객 자료다.

| 실제 Seed 시나리오 | 결과 |
|---|---|
| Mr.Life·GS25·10,000원·전월 400,000원 + 정책 미등록 카드 | Mr.Life 1,000원, PARTIAL_READY. 미등록 카드만 미비교. 사용량 불변 및 Seed 반올림 NULL 보존 |
| 노리2·앱스토어·유튜브·15,000원·전월 200,000원·공유 잔여 1,800원 | READY·1,800원. 두 상세 NULL·미배분 묶음 유지. 모의 규칙과 함께 실제 DB JSON 저장/조회 |
| 현대 M·음식점·100,000원·전월 1,000,000원·우대 잔여 1,000P | READY·우대 1,000P + 기본 1,200P, 비교액 2,200원. 원래 HALF_UP 보존. 공과금/교통 등 기본 모의 범위 밖 제외 사실은 이 가상 거래에 대해 명시 제공 |
| ZERO 할인형의 별도 필수 조건 미확인 | DATA_INSUFFICIENT 유지. 모든 조건을 false로 채우지 않음 |
| 12월 거래·승인되지 않은 혜택 버전·이미 종료된 원본 | 승인된 11월 모의 정책으로 되살리지 않음 |

JSON 원시 BigDecimal은 형식 검증 트리를 변환 데이터로 재사용하지 않고 원문에서 복원해 소수 정밀도를 보존한다. 프로젝트 공통 UTC 시간 JSON은 초 단위이며 MySQL JSON은 숫자의 소수 자릿수 표기를 정규화하므로, DB 테스트는 금액의 수치·NULL·규칙 기록을 검사한다.

전체 BE 회귀 초회는 1,301개 실행·12개 실패·생략 0이었다. 기존 POS/승인 테스트가 조건부 Mock 빈을 전제로 한 11건은 테스트 구성에 기존 `MockCardRecommender`를 명시적으로 주입해 해결했다. 승인 실행 코드나 실제 추천의 availability UNKNOWN 정책을 완화하지 않았다. 나머지 1건은 MySQL 숫자 표기의 scale 차이를 금액의 수치 비교로 보완했다. 해당 5개 클래스 및 새 계산 경계의 재실행 **81개 전부 통과**다. 실제 Seed 추천 테스트와 POS/승인 Mock fixture 검증을 구분한다. 실패와 무관한 전체 테스트를 다시 반복 실행하지 않았다.

다른 세션의 미추적 `Nori2SeedRecommendationDatabaseTest.java`는 수정·커밋하지 않았고 전체 검사에서 제외했다. 그 파일은 모의 정책 적용 이전의 DATA_INSUFFICIENT 기대를 고정한 진단 파일이다. 아래 전체 명령의 init script는 그 파일 한 개만 제외하며 테스트 결과를 성공으로 바꾸는 처리는 없다.

```powershell
.\gradlew.bat test --init-script build/recommendation-tracked-tests.init.gradle
# init script: allprojects { tasks.withType(Test).configureEach { exclude '**/Nori2SeedRecommendationDatabaseTest.class' } }
.\gradlew.bat test --tests 'com.yamu.be.recommendation.SimulationSeedRecommendationDatabaseTest' --tests 'com.yamu.be.recommendation.BenefitCompositionTest' --tests 'com.yamu.be.payment.approve.PaymentApprovalIntegrationTest' --tests 'com.yamu.be.payment.approve.CheckCardApprovalIntegrationTest' --tests 'com.yamu.be.payment.pos.PosPaymentRequestIntegrationTest'
```

그 뒤 실제 Seed 테스트에 미승인 버전·종료 원본 경계를 추가해 해당 클래스 **6개 전부 통과**를 확인했다. 초회 결과에서 재실행한 클래스의 결과를 교체해 집계하면 고유 테스트 **1,304개·실패/오류/생략 0**이며 이 중 MySqlTestSupport 기반 실제 MySQL 테스트는 **447개**다. 이는 수정 후 전체 단일 실행 통과 기록이 아니라 전체 초회와 변경된 범위 재실행을 합친 기록이다. Seed 생성 Node 테스트 1개와 `git diff --check`도 통과했다.

개발/운영 DB 적용, 실제 POS→앱의 추천 성공 E2E, 결제 가능 상태 공급, 미래 환산값의 소수 화면 표시, 다중 공유 한도 네트워크는 검증하지 않았다. 기존 안전한 retryable 오류 정보는 유지하며 자동 재조회는 새로 추가하지 않는다.

## Primary Notion 반영 상태·문안

POL-012·POL-013·카드 추천 공통 처리 페이지를 로그인된 사이드 브라우저에서 직접 읽었다. 본문 편집은 clipboard 대상 불일치/입력 시간 초과로 실패했고 POL-012 원문 보존을 다시 확인했다. 페이지 수정 완료로 간주하지 않는다. 다음 문안을 해당 추천 정책 절에 추가할 수 있다. 기존 약관 기준과 추가 비용 0원 정책은 보존한다.

> 2026-11-03 사용자 일괄 승인: YAMU_BASIC_PURCHASE/1은 확인값 우선·승인된 일반 국내 직접 구매 사실만 보완하며 채널·공식 경로·제휴 수단·실적·사용량·결제 가능성은 추정하지 않는다. YAMU_SIM_CARD_POLICY/1은 Seed 17종·56개 혜택의 버전 1 및 한국 시간 2026년 11월에만 적용한다. 누락 반올림은 DOWN·지급 단위 1·거래별이며 확인된 값은 보존한다. 기존 LIMIT_THEN_ROUND와 잔여 한도 지급을 유지한다. 누락 기간은 [2026-11-01, 2026-12-01)과 알려진 경계의 교집합이다. 누락 포인트 환산은 M ME4 3개·ZERO 포인트형·taptap O·트래블로그 각 1개에만 모의 비교용 1P=1원이다. 한 혜택에만 연결된 COUNT는 PER_BENEFIT·0원 미차감이다. 미등록 관계·필수 사실·집계 누락은 계속 미확인으로 남긴다. 원본 Seed NULL은 유지하고 적용 규칙 ID/버전·필드·카드·혜택을 simulationRules와 결과 JSON에 보존한다. 안내는 ‘YAMU에 등록된 혜택과 모의 계산 기준으로 추천했습니다. 포인트의 비교 금액은 실제 환급액과 다를 수 있습니다.’이다. 실제 상품 약관 전체 확인이나 승인·청구·차감 구현 완료를 뜻하지 않는다. 노리2의 사용자 전달 카드사 전화 상담과 현대 M의 공식 기본 전환·승인된 원금 분할 해석은 이 모의 규칙과 구분한다.

## 2026-11-12 ZERO 해외 조건·처음 쿠팡 보완

사용자 승인 범위: ZERO 두 상품의 국내외겸용 제한은 해외 거래일 때만 판단한다. 원본 DB 조건은 삭제하지 않고, assembler에서 해당 상품·혜택에 한정해 `RULE:ZERO_OVERSEAS_ELIGIBILITY`로 의미를 변환한다. 국내는 통과, 해외는 `CARD_FORM:GLOBAL`의 확인값으로 판단하며 지역 미확인·모순 또는 해외 카드 속성 누락은 UNKNOWN이다. 다른 상품의 `CARD_FORM:GLOBAL`은 변경하지 않는다. 조건 변환은 simulationRules의 적용 필드에 남긴다.

근거: 현대카드 ZERO Edition3 할인형 공식 상품 안내의 ‘해외 이용 금액 할인은 국내외겸용 카드에 한해 적용’ 및 포인트형 공식 가이드북의 동일 적립 제한. 국내전용 카드를 국내외겸용으로 꾸며서 계산하지 않는다.
- https://www.hyundaicard.com/cpc/cr/CPCCR0201_01.hc?cardWcd=ZROE3&eventCode=SBG79
- https://www.hyundaicard.com/upload/card/ZERO%20Edition3%28%ED%8F%AC%EC%9D%B8%ED%8A%B8%ED%98%95%29%20%EC%83%81%ED%92%88%20%EA%B0%80%EC%9D%B4%EB%93%9C%EB%B6%81.pdf

처음 쿠팡: 서버 merchant 조회 결과가 COUPANG이고 온라인 일반 모의 구매인 경우에만 공식 앱 직접 구매·포인트 미사용·배달앱/상품권 거래 아님을 채운다. `YAMU_CHEOEUM_COUPANG_PURCHASE/1`로 추적한다. 명시된 사실은 보존하며 특수 거래·자동납부·대행 경로는 기본값을 적용하지 않는다. 원본 거래는 불변이며 해당 카드 계산·실적 인식용 복사본만 사용한다. 다른 온라인몰에는 이 가정을 확대하지 않는다. 위 본문의 102→93건 기록은 이 보완 이전 결과다.

검증 결과: 동일 765개 조합에서 NOT_COMPARED 93→0, 양수 200→293, 0원 472 유지. 기존 CALCULATED 672건의 금액 변경 0건. 별도 기대 금액 대조 291건 통과. 추천 관련 회귀 초회 348개 중 쿠팡을 OFFLINE으로 구성하던 fixture 때문에 4개 실패했고, ONLINE으로 수정 후 변경 범위 102개 재실행 통과. 클래스별 최신 결과를 합친 349개는 실패·오류·생략 0이며 전체 단일 실행 통과 기록은 아니다. 스타벅스 및 쿠팡 21,600원은 전월실적 30만원 미만 0, 30만원 이상 테스트에서 500P를 확인했다. 해외 속성 누락은 UNKNOWN, 국내전용 해외는 부적격, 국내전용 국내는 정상 계산한다. 삼성 taptap O·iD SIMPLE의 일부 혜택 미적재 문제는 별개로 남는다.

검증 리포트(로컬 build 산출물): `be/build/reports/recommendation-audit-zero-coupang-20261112.html`. 이전 리포트는 보존했다. DB 스키마·시드 SQL·운영 데이터는 변경하지 않았으며 배포·실제 앱 E2E는 수행하지 않았다.

457 MR 최종 검증: origin/develop 455df481 기반 fix/be/S15P31E103-457-simulation-facts에서 추천·결제 추천·실적 조립 테스트 463개를 단일 실행해 실패/오류/생략 0을 확인했다. 최신 develop의 '사실을 넘기지 않음' 테스트는 기본 모의 보완을 적용하지 않은 빈 사실로 입력을 바로잡아 UNKNOWN 보호 의미를 유지했다. 동일 765개 조합 모두 CALCULATED(양수 293, 0원 472), 기대 금액 대조 291건 통과. 이전 349개 합산 기록과 구분한다.
