# 카드 이미지 매핑 확인 결과

기준: 기존 상품의 `issuer_code + product_name + card_type`. 실제 DB 조회 결과가 아닌 정적 대조 자료다.
수집 ID는 자료 연결용이며 RDS PK로 사용하지 않는다. SQL은 기존 상품의 NULL 이미지에만 적용한다.

| 적용 상태 | 카드 수 |
|---|---:|
| APPLY | 17 |
| HOLD | 1 |
| EXCLUDED | 2 |

## 상품별 대조

| 수집 ID | DB 카드사 | DB 상품명 | 종류 | 상태 | 확인 사항 |
|---|---|---|---|---|---|
| hana-travelog-check | HANA | 하나카드 트래블로그 체크카드 | CHECK | APPLY | 기존 Seed 상품명과 일치 |
| hana-wonder2-daily | HANA | 하나카드 원더카드 2.0 DAILY | CREDIT | EXCLUDED | NO_SUPPORTED_BENEFITS: 기존 카탈로그에서 지원 가능한 혜택이 없어 제외 |
| hyundai-m | HYUNDAI | 현대카드 M ME4 | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| hyundai-mzroe3 | HYUNDAI | 현대카드ZERO Edition3(포인트형) | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| hyundai-zero-edition3-discount | HYUNDAI | 현대카드 ZERO Edition3 (할인형) | CREDIT | APPLY | 원문 상품명과 DB 상품명 표기가 달라 기존 Seed 이름에 명시 매핑 |
| kakaobank-friends-check | KAKAOBANK | 카카오뱅크 프렌즈 체크 | CHECK | APPLY | 기존 Seed 상품명과 일치 |
| kb-01664 | KB | KB국민 노리 체크 | CHECK | APPLY | 기존 Seed 상품명과 일치 |
| kb-07964 | KB | KB국민 노리2 체크카드(KB Pay) | CHECK | APPLY | 기존 Seed 상품명과 일치 |
| kb-toktok | KB | KB국민 청춘대로 톡톡카드 | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| lotte-p13943-a13943 | LOTTE | LOCA LIKIT Eat | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| lotte-p13949-a13949 | LOTTE | LOCA LIKIT Play | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| lotte-p13955-a13955 | LOTTE | LOCA LIKIT Shop | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| lotte-p15835-a15835 | LOTTE | LOCA LIKIT 2.0 | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| samsung-aap1767 | SAMSUNG | 삼성 iD SIMPLE | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| samsung-id-on | SAMSUNG | 삼성 iD ON 카드 | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| samsung-taptap-o | SAMSUNG | 삼성 taptap O | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| shinhan-biabix | SHINHAN | 신한카드 Deep Oil | CREDIT | EXCLUDED | NO_SUPPORTED_BENEFITS: 기존 카탈로그에서 지원 가능한 혜택이 없어 제외 |
| shinhan-cheoeum | SHINHAN | 신한카드 처음 | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| shinhan-mrlife | SHINHAN | 신한카드 Mr.Life | CREDIT | APPLY | 기존 Seed 상품명과 일치 |
| shinhan-pointplan | SHINHAN | 신한카드 Point Plan | CREDIT | HOLD | POINT_PLAN_POLICY_HOLD: 일반월·가족행사월 대체 한도 지원 전 카드 전체 적재 보류 |

## 적용 시 확인할 항목

- MR !75의 image_key 컬럼이 먼저 필요하다. 이번 SQL에는 스키마 변경이 없다.
- 대표 3종만 있는 DB: 3개 매핑, 나머지 14개 MISSING_PRODUCT. 추가 상품을 만들지 않는다.
- 기존 17종이 있는 DB: 17개 매핑. 보류 1종·제외 2종은 SQL에 포함하지 않는다.
- 이미 같은 키가 있으면 재실행 변경 0건. 다른 키·빈 문자열은 보존하고 충돌로 보고한다.
- 가맹점·혜택·사용자 데이터와 상품의 다른 컬럼은 갱신하지 않는다.
- 현재 RDS의 존재 상품·기존 이미지 충돌 여부는 미확인이다. 적용 담당자가 SQL의 사전/사후 결과를 확인한다.
