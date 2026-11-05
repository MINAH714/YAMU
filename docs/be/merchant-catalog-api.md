# 업종·브랜드 목록 API 인계 — S15P31E103-297

2026-11-05 승인 설계. 두 목록 GET만 인증 없이 공개한다. 공통 `ApiResponse`를 사용하며 기존 POS·사용자 API의 인증 방식은 유지한다. 데이터는 기존 브랜드 단위 `merchant`에서 읽는다. DB 스키마·Seed의 브랜드 업종 배정은 변경하지 않았다.

## 업종 목록

`GET /api/merchant-categories`

```json
{
  "success": true,
  "data": [
    {"merchantCategoryCode": "OTHER", "merchantCategoryName": "기타"},
    {"merchantCategoryCode": "COFFEE_SHOP", "merchantCategoryName": "카페"}
  ],
  "error": null
}
```

위는 목록 발췌다. 실제 응답은 49개 지원 업종을 포함한다. 평면 목록이며 표시 이름순, 동명은 코드순이다. 브랜드가 하나도 없는 지원 업종도 포함한다. 물품 분류나 거래 조건을 업종으로 반환하지 않는다.

## 업종별 브랜드 목록

`GET /api/merchants?merchantCategoryCode=OTHER`

```json
{
  "success": true,
  "data": [
    {"merchantId": 301, "brandCode": "APP_STORE", "merchantName": "앱스토어", "merchantCategoryCode": "OTHER"}
  ],
  "error": null
}
```

예시 ID는 설명용이며 실제 ID는 DB에서 발급한 값이다. `OTHER`에는 실제 Seed의 구글플레이·롯데월드·앱스토어·에버랜드 등이 포함된다. 표시 이름순, 동명은 merchantId순으로 정렬한다.

`merchantCategoryCode`는 필수다. 대소문자·접두어·공백 보정을 하지 않고 코드 정확 일치로 조회한다. 유효 업종에 브랜드가 없으면 200과 `data: []`를 반환한다. 전체 브랜드 검색·페이지 구분·계층 조회는 지원하지 않는다. 미확인 값을 OTHER로 바꾸지 않으며 OTHER는 다른 모든 업종과 일치하는 코드가 아니다.

누락/빈값/잘못된 코드에는 HTTP 400을 반환한다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "잘못된 요청입니다.",
    "details": {"fieldErrors": [{"field": "merchantCategoryCode", "reason": "지원하는 가맹점 업종 코드를 입력해 주세요."}]}
  }
}
```

## 시뮬레이션 담당자 연결

목록 HTTP API는 연결되어 있다. 실제 가상 구매 HTTP 입력 처리에는 아직 연결하지 않았으며 아래 서버 내부 검증 메서드를 제공한다.

```java
MerchantCatalogService.Selection selection = catalog.resolveSelection(request.merchantId(), request.merchantCategoryCode());
// Transaction에는 selection.merchantId(), selection.merchantCategoryCode()를 전달한다.
// facts, purchaseItemCode, simulationRules는 각각 확인한 값/실제 적용한 근거를 별도로 공급한다.
```

- 브랜드 미선택/선택 해제: `merchantId=null`, 선택한 업종 유지. 지원 업종 검증 후 DB 브랜드 조회 없이 반환한다.
- 브랜드 선택: DB 행을 merchantId로 읽고 저장된 업종을 사용한다. 요청 업종이 함께 있고 다르면 `INVALID_REQUEST`, `fieldErrors.merchantCategoryCode`로 거부한다.
- 없는 브랜드/0 이하 ID: `INVALID_REQUEST`, `fieldErrors.merchantId`로 거부한다. DB 업종이 지원 목록에 없으면 실패하며 OTHER로 바꾸지 않는다.
- 브랜드만 있고 업종이 생략되면 DB 업종을 반환한다. 이는 브랜드 목록 API의 필수 업종 필터와 별개인 내부 선택 검증 계약이다.
- 브랜드로 온라인/오프라인·시설·구매 항목을 추론하지 않는다. `merchantCategoryCode`는 업종, `productCategoryCode`는 물품 분류, `purchaseItemCode`는 구매 항목이다.
- 브랜드 미입력 계산 옵션은 Task 296의 3인자 calculate를 시뮬레이션에서 명시적으로 사용한다. 이 Task는 옵션이나 일반 구매 가정을 자동 적용하지 않는다.

## 업종 표시명 근거와 충돌 정리

서버 `MerchantCategories`의 작은 고정 목록을 사용한다. 원본 `be/seed/catalog-ab20/codes/merchant-categories.csv`는 원본 분류→YAMU 코드 매핑이며 API 목록 자체가 아니다. CONDITION 행은 제외하고 복수 코드는 각각 분리했다. 기존 Seed SQL의 CATEGORY 대상과 merchant 업종도 대조했다.

- `BAKERY`: 중복 라벨 베이커리·제과점/제과점/베이커리를 기존 포괄 라벨 `베이커리·제과점`으로 통일.
- `TELECOMMUNICATION`: 통신/이동통신을 기존 상위 라벨 `통신`으로 통일.
- 음식점 복수 코드: `음식점 전체`라는 동일 이름을 7개에 복제하지 않고 기존 Seed 검토 문구의 한식·양식·일식·중식·뷔페, 사전의 일반음식점·패스트푸드 사용. 해당 검토 문구는 표시명 근거로만 사용하며 미검증 약관을 새로 활성화하지 않았다.
- `BOOKSTORE`, `MEMBERSHIP_SUBSCRIPTION`: 브랜드 코드 사전의 서점·유료 멤버십 문구 사용. `LAUNDRY`는 Seed의 세탁소 문구 사용.
- 기존 코드에 표시명이 없던 7개는 사용자에게 확인하여 2026-11-05 승인받음: HOSPITAL=병원, DENTAL_CLINIC=치과, KOREAN_MEDICINE_CLINIC=한의원, VETERINARY_CLINIC=동물병원, HYPERMARKET=대형마트, SUPERMARKET=슈퍼마켓, HEALTH_BEAUTY_STORE=헬스·뷰티 매장.

기존 CSV·SQL을 수정하거나 브랜드를 재분류하지 않는다. 새 업종 추가 시 코드/이름 근거와 이 목록을 함께 갱신해야 한다. DB에 임의 추가된 미지원 코드를 자동으로 노출하지 않는다.

## 검증 및 제약

2026-11-05 최종 실행: 목록 API·선택 검증·기존 바코드 인증 회귀 총 26건 통과, 실패/오류/생략 0건. 실제 Seed를 적재한 MySQL DB 테스트 1건 포함.

`MerchantCatalogControllerTest`: 실제 SecurityConfig를 적용한 MVC 테스트. 공개 조회, 기존 응답 형식, 정상 빈 목록, 필터 누락/잘못된 코드, 다른 메서드·하위 경로·결제 경로 인증 유지.

`MerchantSelectionTest`: 브랜드 없는 업종 입력, 브랜드 선택/해제, DB 업종 사용, 불일치/없는 브랜드 거부, 계산 Transaction 입력 연결, 코드 사전 전체와 지원 목록 대조.

`MerchantCatalogDatabaseTest`: Testcontainers MySQL + 실제 Flyway + 기존 Seed SQL. 업종 코드 전체 포함, OTHER 브랜드, 빈 업종, 정확 일치, 동명 ID 정렬, 선택 검증과 금융 상태 미변경 확인. 운영/개발 DB를 사용하지 않는다.

```powershell
.\gradlew.bat test --tests 'com.yamu.be.merchant.*' --tests 'com.yamu.be.payment.barcode.BarcodeControllerTest'
```

DB 오류를 빈 배열로 감추지 않는다. 조회는 읽기 전용이며 상태 갱신·차감이 없으므로 동시 조회/재시도로 금액을 중복 차감하지 않는다. 카드/사용자 소유권 판정은 기존 시뮬레이션 입력 공급 계층이 수행해야 한다. 금융망 요청·승인 처리·잔고예보 계산은 추가하지 않았다.
