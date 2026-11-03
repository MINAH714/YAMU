# 구매 항목·대상 경로·혜택 조합 연결

2026-11-03 승인 설계 구현. 현재 브랜치의 코드 계약이며 운영 DB 적용/실제 결제 완료를 의미하지 않는다.

## 조회와 계산

`DatabaseCardRecommender` → `RecommendationService` → 기존 카드/상품 Repository → `RecommendationPolicyLoader` → `RecommendationInputAssembler` → 기존 Calculator/Selector를 사용한다. 실제 포트가 등록되면 기존 조건부 Mock 빈은 등록하지 않는다. POS HTTP 접수와 승인/청구 서비스는 이 브랜치에 없으므로 HTTP부터 연결된 기능은 아니다.

구매 항목은 merchantId와 별개인 purchaseItemCode다. 신규 POS 선택은 `PurchaseItemReader.validateNewSelection(code)`로 등록·활성을 확인하고 최초 접수 트랜잭션에서 보존한다. 미지정은 NULL이며 다른 상품 구매로 간주하지 않는다. 보존 거래의 추천/재시도는 `validateStored`를 사용해 비활성화 후에도 원래 코드를 재현한다. 사용된 코드의 삭제/의미 변경/재사용은 금지하며 비활성화로 신규 선택을 제한한다. 상품 카테고리/가맹점 허용 목록을 대신 만들지 않는다.

대상 그룹과 멤버, 적용 그룹과 멤버, 정책 구매 항목 등록값을 ID 집합으로 일괄 조회한다. 대상 그룹은 같은 혜택만, 적용 그룹은 같은 상품의 특정 혜택 버전만 연결한다. 대상 그룹 내 AND/그룹 간 OR, 제외 경로, 기존 공통 CONDITION을 세 가지 상태로 판단한다. 빈 적용 경로를 항상 적용으로 바꾸지 않는다. 여러 경로가 맞아도 동일 혜택은 한 번만 계산한다.

새 `applicationGroups` 입력은 STACK/MAX_ONE/PRIORITY_ONE을 표현한다. 기존 `allowedCombinations` 생성자와 계산 경로는 유지하며 새 DB 입력은 적용 그룹을 사용한다. MAX_ONE은 같은 거래 전 사용량에서 독립 계산한 경제적 혜택을 비교한다. PRIORITY_ONE은 조건에 맞는 우선 항목을 선택하며 한도 소진만으로 다음 항목을 실행하지 않는다. 같은 우선순위에서 둘이 맞으면 미확인이다. 그룹 없는 혜택은 대상 불일치가 확인된 다른 혜택과 독립 처리하고, 실제 거래에서 중첩되는데 관계가 없으면 `BENEFIT_RELATIONSHIP_UNKNOWN`이다.

STACK은 개별 한도를 반영한 값과 공유 금액 한도를 구분한다. 동일 단위의 한 공유 금액 한도에 함께 걸리는 양수 혜택에서 합계가 잔여를 넘으면 총액은 잔여로 확정한다. 상세 value/krwValue는 NULL, `unallocatedBenefitGroups`에 혜택 ID·공유 한도 ID·총액·단위·SHARED_LIMIT_ALLOCATION_UNRESOLVED를 남긴다. 해당 묶음은 합계에 한 번만 넣는다. 배분을 알 수 없는 개별 한도/횟수의 사용 예상치는 제공하지 않으며 공유 금액 사용 예상치만 확정한다. 미배분만으로 complete/READY/PARTIAL_READY를 바꾸지 않는다.

다중 공유 금액 한도 네트워크, 서로 다른 적용 그룹 사이의 공유 한도 순서, 부족한 공유 횟수의 배분 등 이번 Seed/설계로 결정하지 못한 토폴로지는 각각 구체적 미확인 사유로 남긴다. LIMIT_THEN_ROUND를 바꾸지 않으며, 공유 한도가 실제로 충돌하는 이 순서의 배분을 임의 확정하지 않는다. 정액은 기존대로 잔여 한도까지 지급하고 신규 전액 미지급 설정은 없다.

현대 M의 우대 혜택은 금액 한도 연결이 정확히 하나인지 검사하고, 남은 포인트 한도/우대율로 반올림 전 원금을 분할한다. baseAmount 입력을 바꾸지 않고 해당 계산 호출에만 원금을 전달한다. 기본 조건·실적·반올림·한도를 다시 검사하며 기본을 전체 원금에 중복 실행하지 않는다. 우대와 기본 대상 원금이 달라 분할 범위를 확인할 수 없거나 비종결 소수로 임의 원금 반올림이 필요하면 별도 미확인 사유를 제공한다. 현재 Seed의 5% 분할은 이 소수 제한에 걸리지 않는다. 자기 참조·연쇄 전환은 거절한다.

## Seed와 근거

새 Flyway `V20261103_1`은 기존 대상을 경로로 이관하고 비CONDITION의 is_excluded를 FALSE로 정규화한다. 같은 대상이 적용/제외 양쪽에 있어도 두 경로를 남긴 뒤 대상 행을 통합한다. 경로 코드는 타입·브랜드 코드/대상 코드·적용/제외 의미로 만든 해시이며 자가 증가 ID를 사용하지 않는다. MySQL은 AUTO_INCREMENT 컬럼을 CHECK에서 참조할 수 없으므로 fallback 자기 참조는 INSERT/UPDATE 트리거로 차단한다. [MySQL 8.4 제약](https://dev.mysql.com/doc/refman/8.4/en/numeric-type-attributes.html).

Docker 활성화 후 실제 실행에서 기본 MySQL 바이너리 로그 설정의 트리거 생성 권한 오류(1419)를 확인했다. `MySqlTestSupport`의 폐기 가능한 컨테이너에만 `--log-bin-trust-function-creators=1`을 지정한다. 개발/운영 DB 설정은 변경하지 않았다. 실제 배포 전 DBA는 마이그레이션 계정의 TRIGGER 권한과 바이너리 로그 환경의 생성 권한 또는 해당 서버 설정을 준비해야 한다. 이 조건이 없으면 기본 설정에서 이 마이그레이션이 실패하며 MySQL DDL은 전체 롤백되지 않으므로 적용 전에 확인한다. 자동 clean/repair로 우회하지 않는다.

현재 신규 적재 순서는 Flyway → `01-card-catalog.sql` → `02-card-catalog-ab20.sql` → `03-benefit-composition.sql`이다. 01/02도 새 구조의 경로를 생성하므로 새 DB와 기존 DB 재실행을 모두 지원하도록 변경했다. 02는 기존 생성 결과를 `target-groups.mjs` 변환기로 갱신했으며 이후 원본 생성기도 같은 변환기를 사용한다. 03은 명시적 승인 관계를 적재하는 별도 반복 실행 가능한 SQL이다. 기존 로컬 Seed CLI는 01만 실행하는 기존 범위를 유지하므로 17종 적재에는 02/03을 명시적으로 실행해야 한다. 코드 작업 중 실제 DB에 적재하지 않았다.

- 노리2 모바일·구독 STACK, 앱스토어 AND YOUTUBE_PREMIUM_SUBSCRIPTION 경로는 **사용자 전달 카드사 전화 상담** 근거다. 공개 약관 직접 확인으로 표현하거나 다른 구독·결제 경로로 확대하지 않는다. 기존 직접 결제 경로는 OR로 보존한다. 기존 공통 제외 사실을 임의로 false로 만들지는 않는다.
- 카카오뱅크는 Seed에 있는 기본과 현재 추가 혜택만 STACK으로 연결한다. MAX_ONE 관계를 자동 생성하지 않는다.
- 현대 M은 두 우대 순위 1, 기본 순위 2와 한 단계 fallback을 적재한다. 우대의 국내/해외 공통 CONDITION을 보존한다. ‘한도 초과 시 기본 적립률’은 공식 문구, 같은 거래의 원금 분할은 승인된 설계 해석이며 공식 숫자 예시 확인으로 기록하지 않는다.

새 구조가 기존 미확인 숫자를 확인한 것은 아니다. `complete=false`, 확인되지 않은 조건/버전, 계산 순서·기준액·포인트 원화 환산·반올림/기간·횟수 정책의 미확인은 보존한다. 확인된 관계가 있으면 조합 미등록 사유만 해소하며 17종 모두 실제 CALCULATED/READY가 된다고 보증하지 않는다.

## 결제 담당 호출 계약

`RecommendationRequest`에 최초 보존한 purchaseItemCode, 검증한 verifiedFacts, 카드별 availability를 전달한다. 기존 MerchantContext는 결제처/업종/채널/시설 용도다. 누락된 카드 결제 가능 여부는 AVAILABLE이 아니라 UNKNOWN이다. 새 `DatabaseCardRecommender`는 별도 네트워크/재시도 없이 내부 서비스를 호출하며 후보·제외·미비교 및 조회 사유를 반환한다.

`RecommendationResult.schemaVersion=1`, 후보별 netBenefit은 필수 숫자(BigDecimal), benefits[].amount는 정수 또는 NULL, unallocatedBenefitGroups는 필수 배열이다. 원시 금액은 BigDecimal로 보존해 정률 소수를 버리지 않는다. 포인트 환산 후 경제적 순혜택 소수도 정수로 절사하지 않는다. RuleVersion은 혜택별 실제 버전을 보존하고, 하나의 공통 버전이 없으면 단일 version은 NULL이다.

`instantDiscount()`는 확인된 즉시할인 상세와 전부 즉시할인인 미배분 묶음의 총액만 한 번씩 합산한다. 즉시/이후 반영이 섞여 즉시금액이 불명확하거나 할인 반영 시점이 없으면 0으로 대체하지 않고 거절한다. 실적 인정 Resolver도 NULL 상세를 할인 없음으로 오해하지 않는다.

```java
// 결제 담당의 기존 접수 트랜잭션: 소유권·불변 요청 비교·잠금은 호출자가 처리한다.
snapshotStore.preservePurchaseItem(userId, paymentId, validatedPurchaseItemCode);
RecommendationResult result = cardRecommender.recommend(preservedRequest);
snapshotStore.save(userId, paymentId, result);
// 위 트랜잭션 커밋 성공 이후에만 저장 완료로 응답한다.
// 앱 조회: 재계산하지 않는다. 과거 NULL이면 Optional.empty().
snapshotStore.find(userId, paymentId);
```

`RecommendationSnapshotStore`의 쓰기는 MANDATORY 트랜잭션이며 사용자·거래로 필터링한다. 구매 항목/추천 JSON 및 수정 시각/버전만 변경한다. 실적·사용량·승인 금액·상태를 갱신하지 않는다. 저장 실패는 예외이며 저장 완료 결과를 반환하지 않는다. 호출자는 동시 저장·재시도·중복 요청·승인 재검증을 담당하고, 미배분 결과를 payment_benefit의 승인 확정 내역으로 복사하면 안 된다. 기존 HTTP API/앱 화면은 새로 만들지 않았다.

## 검증 구분

순수 조건/계산 테스트는 확인된 가상 정책을 사용한다. 실제 조립기/서비스 경로 테스트는 complete=false를 true로 덮어쓰지 않고 새 그룹이 엔진에 전달됨을 검사한다. JSON·저장 호출 단위 테스트와 MySQL 마이그레이션/Seed/JPA/보존 테스트를 구분한다. Docker 엔진 연결 불가 시 DB 테스트는 생략되며 BUILD SUCCESSFUL만으로 실제 DB 제약/저장 검증 통과를 보고하지 않는다.

2026-11-03 Docker 활성화 전 기록: 관련 Gradle 테스트 **253개 중 211개 통과, 실패·오류 0, MySQL 테스트 42개 생략**. 새 조합/실제 조립/구매 항목/결제 포트→서비스→Selector 연결, JSON NULL/0 보존, Mock 대체 빈 구성을 포함한다. 이때의 MySQL 검증은 Docker 엔진 연결 불가로 생략됐으며 아래에 실제 실행 결과를 구분한다.

같은 날 Docker 활성화 후 위 관련 범위를 다시 실행해 **253개 전부 통과, 실패·오류·생략 0**을 확인했다. 포함된 실제 MySQL 8.4.11 테스트는 42개다. 기존 데이터의 적용/제외 경로 보존 이관, 17종 Seed 재실행, 복합 FK와 자기 참조 차단, 실제 JPA 조회·월/사용량 필터, 실적 공급과 값 불변, 사용자별 추천 JSON 저장·조회가 통과했다. 최초 실행은 트리거 생성 권한 오류 1419로 DB 컨텍스트가 실패했다. 테스트 전용 컨테이너 설정을 반영한 뒤, 저장 거절 예외를 Spring이 InvalidDataAccessApiUsageException으로 감싸는 실제 동작에 맞춰 테스트 기대 타입을 보완했다. 소유권 거절 로직을 완화하지 않았다.

이후 보존 테스트에 총액 1,800원·상세 NULL·확인된 0원 fixture를 추가해 `RecommendationSnapshotDatabaseTest`를 별도 재실행했고 **1개 통과, 실패·오류·생략 0**이다. 실제 MySQL JSON_TYPE/JSON_EXTRACT와 저장 객체 복원으로 NULL/0 구분을 검사했다. 이 fixture의 저장 검증은 실제 상품의 계산 정책 완전성 검증이 아니다. 개발/운영 DB에는 적용하지 않았고, 전체 BE·POS/앱 E2E·승인 동시성은 실행하지 않았다.

실행 명령(작업 디렉터리 be):

```powershell
.\gradlew.bat test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.global.schema.CardCatalog*Test' --tests 'com.yamu.be.global.schema.BenefitCompositionMigrationTest' --tests 'com.yamu.be.global.schema.InitialSchemaMigrationTest' --tests 'com.yamu.be.card.RecommendationPerformanceAssembler*'
```

Seed 생성 회귀 `node --test be/seed/catalog-ab20/build-seed.test.mjs`는 **1개 통과**다. 원본 ZIP 없이 테스트용 입력으로 생성기와 동일 변환기를 실행한 검증이며, 원본 20종 재가공이나 실제 SQL 실행 검증이 아니다. 중간 실행에서 구형 JSON의 새 필드 NULL 처리 및 생성자 오버로드/미확인 사유 회귀를 수정했으며 위 수치는 수정 후 마지막 실행의 XML 기준이다. `git diff --check`도 통과했다.

예상 한도 사용량은 후보의 expectedLimitUses로 전달한다. 미배분 충돌의 개별 한도·횟수는 공급하지 않으며, 이는 승인 확정 기록이 아니다. 사용량·실적·잔액·신용한도 갱신, 승인·청구·취소·복구·멱등성·동시 저장 구현은 추가하지 않았다.
