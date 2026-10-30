# 카드 20종 v2 가공 시드 (S15P31E103-178)

A·B 파트가 모은 `YAMU-AB-카드20종-통합자료-v2-20261029.zip`을 지금 DB 스키마에 맞게 가공한 결과입니다. 97 시드(`../01-card-catalog.sql`)에 이미 있는 3장(Mr.Life · ZERO 할인형 · 처음)은 그대로 두고, 추가 14장을 `../02-card-catalog-ab20.sql`로 넣습니다. Deep Oil과 원더카드 2.0 DAILY는 넣을 수 있는 혜택이 없어 제외합니다. **Point Plan은 일반월·가족행사월 대체 한도 지원 전까지 카드 전체 적재를 임시 보류**합니다(MR !41 리뷰 후 팀 승인). 보류 사유와 혜택별 내역은 제외 보고서에 남깁니다.

업종 · 조건 코드는 **임시**입니다. 가맹점 업종 목록(POL-004)이 아직 미정이라 97 시드가 쓴 코드 모양을 따라 만들었고, BE · POL-004 담당 확인이 필요합니다.

## 수집 원본과 가공 결과
| 무엇 | 어디 |
|---|---|
| 수집 원본 ZIP(58MB, PDF · 이미지 포함) | 저장소 밖. 받은 그대로 보존. 가공에 쓴 `data/*.csv` 8개의 sha256은 SQL 첫머리에 적었다 |
| 손으로 쓴 임시 사전 | `codes/merchant-categories.csv`(수집 업종 → YAMU 업종 · 거래 종류), `codes/merchant-brands.csv`(97과 다른 브랜드 키 · 업종이 빈 브랜드) |
| 생성된 사전 | `codes/conditions.csv` — 이번에 쓴 조건 코드 130개와 근거 |
| DB 표 모양 CSV | `db/*.csv` — 자동 번호 대신 상품 열쇠(카드사 · 상품명 · 종류)와 코드로 잇는다 |
| 보고서 | `report/excluded.csv`(넣지 않은 행과 이유), `report/assumptions.csv`(NULL로 둔 값 · 남긴 미확인 행), `report/unverified-conflicts.md`(20장 미확인 · 충돌 목록), `report/summary.json` |
| 적재 SQL | `../02-card-catalog-ab20.sql` (생성 파일. 직접 고치지 않는다) |

## 다시 만들기
```
node be/seed/catalog-ab20/build-seed.mjs <ZIP을 푼 YAMU-AB-카드20종-통합자료-v2-20261029 폴더>
```
Node 18 이상, 외부 패키지 없음. 같은 입력이면 같은 출력이 나온다. 사전을 고치면 다시 돌리고 `CardCatalogAb20SeedTest`의 행 수를 맞춘다.

회귀 테스트: `node --test be/seed/catalog-ab20/build-seed.test.mjs`.
원본 ZIP 없이 임시 입력으로 Point Plan 전체 보류·다른 카드 유지·동일 입력 재생성을 검증하며 DB에 접속하지 않는다.
MySQL 통합 테스트의 SQL 로더는 실제 Seed CLI와 동일하게 UTF-8을 명시해 Windows에서도 한글 상품명을 그대로 검증한다.

## 가공 규칙
- **넣는 혜택**: `status = VERIFIED`이고 `yamu_support`가 YES 또는 ASK, 결제 보상(청구할인 · 현장할인 · 캐시백 · 포인트)이면서 정률 · 정액인 행.
- **혜택째 빼는 경우**: 표현할 수 없는 조건이 하나라도 있을 때(결제액 상한, 공휴일, 사용자 선택, 자동 순위, 받으려면 특정 결제수단이어야 하는 조건, 실적 사실, OR · 조건부 논리), 조건이 CONFLICT일 때, 한도가 지원하지 않는 모양(BASE_AMOUNT · 연간 · 평생)이거나 공식 확인 전일 때, 적용 범위(전체 · 브랜드 · 업종)가 없을 때. 조건을 버리고 넣으면 혜택을 실제보다 넓게 주게 되기 때문이다.
- **바꾸는 값**: rate = rate_pct ÷ 100, valid_until + 1일(원본은 그날 포함, DB는 미포함), tier_from → min_performance_amount, 결제액 하한 → min_payment_amount, 요일 · 시간 → conditions_json, 실적 구간별 한도 → tier_limit(상한 = 다음 구간 하한).
- **NULL로 두는 값**: 포인트 1P 가치(point_krw_rate), 반올림 단계(rounding_stage), 원본이 UNKNOWN인 반올림.
- **대상**: 브랜드 → BRAND(merchant 행), 업종 → YAMU 업종 코드(부모 업종은 하위 여러 코드로 펼침), 상품권 · 세금 · 연회비처럼 업종이 아닌 것과 채널 · 시설 · 할부 · 다른 할인 → `접두어:값` 조건 코드. `benefit_key = *` 행은 그 카드의 혜택마다 복사한다.
- **미확인 행**: 미확인 포함 대상은 빼고(범위를 넓히지 않음), 미확인 제외 대상 · 조건은 남긴다(빼면 범위가 넓어짐). 모두 `report/assumptions.csv`에 있다.
- **재실행 안전**: 모든 INSERT는 이미 있으면 건너뛴다. 이미 있는 정책 버전은 덮어쓰지 않는다. 같은 브랜드가 다른 업종으로 이미 있으면 `ab20_guard`에서 멈추고 전부 되돌린다.

## 결과 (`report/summary.json`)
카드 14 · 혜택 43 · 대상 636 · 한도 묶음 33 · 실적 구간 41 · 혜택↔한도 55 · 브랜드 43(97과 겹치는 11개는 97 업종 그대로) · 조건 코드 130. 제외 110행, 가정 98행.

기존에 적재된 Point Plan 행을 삭제하는 SQL은 포함하지 않는다. 아래 예상 행 수는 빈 카탈로그에 적재한 기준이며, 기존 DB 정리는 참조 데이터·사용 이력을 확인한 뒤 별도 검토한다.

## 적재 순서 (인프라 담당 · 개발 DB)
1. 대상 DB에 필요한 Flyway가 적용돼 있어야 한다(`V20261028_3__clarify_limit_units` 포함). 공유 DB의 실제 적용 상태는 담당자가 별도 확인한다. 이 수정은 DB에 접속하거나 적재하지 않는다.
2. 97 시드를 쓰면 `01-card-catalog.sql`, 그다음 `02-card-catalog-ab20.sql`. 02만 넣어도 된다(순서 무관).
3. 실행은 한 번에, 첫 오류에서 멈추게 한다(`mysql --force` 금지). 비밀번호는 명령 인자에 넣지 않고 권한 600인 option 파일을 쓴다. 예(서버에서, 인프라 담당 안내의 개발 DB 설정 파일 사용):
   ```
   mysql --defaults-extra-file=/home/ubuntu/.config/yamu/rds-dev/app.cnf < be/seed/02-card-catalog-ab20.sql
   ```
   PC에서 SSH SOCKS 터널과 DBeaver로 연결했다면 DBeaver에서 스크립트 전체 실행(오류 시 중단)으로 돌린다.
4. 확인 쿼리:
   ```sql
   SELECT COUNT(*) FROM card_product;      -- 01+02: 17, 02만: 14
   SELECT COUNT(*) FROM card_benefit;      -- 01+02: 56, 02만: 43
   SELECT COUNT(*) FROM benefit_target;    -- 01+02: 813, 02만: 636
   SELECT b.rate, b.min_performance_amount, t.limit_value
   FROM card_benefit b
   JOIN card_product p USING (card_product_id)
   JOIN benefit_limit_link l ON l.card_benefit_id = b.card_benefit_id
   JOIN tier_limit t ON t.limit_group_id = l.limit_group_id
   WHERE p.product_name = 'KB국민 청춘대로 톡톡카드' AND b.benefit_code = 'STARBUCKS_50';  -- 0.500000 / 300000 / 10000
   ```
   같은 SQL을 한 번 더 돌려도 위 숫자가 그대로여야 한다.

## 아직 정해지지 않은 것
- 업종 · 조건 코드 전체(POL-004, BE 조건 해석). 97 ZERO는 고속버스를 `TRANSACTION_KIND:EXPRESS_BUS`로, 이번 사전은 업종 `EXPRESS_BUS`로 둬서 하나로 맞춰야 한다.
- 혜택 중첩(`stack_group`)은 DB에 칸이 없어 넣지 않았다(가정 표에 카드별로 남김).
- 결제수단 제외 코드(`PAYMENT_METHOD:...`)는 거래에 결제수단 칸이 없어 지금은 서버가 확인할 수 없다.
- 포인트 1P 가치는 팀 결정 후 새 정책 버전으로 넣는다.
