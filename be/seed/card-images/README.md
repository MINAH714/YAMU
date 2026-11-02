# 카드 상품 이미지 매핑 — S15P31E103-244

수집한 20종의 정적 앞면 이미지 중 기존 Seed에 포함된 **17종**을 상품에 연결하는 별도 SQL이다.
상품·혜택 데이터를 새로 적재하지 않으며, 기존 `LocalSeedLoader`·`load.ps1`의 동작도 변경하지 않는다.

## 선행 작업과 적용 범위

- MR !75 / S15P31E103-243의 `card_product.image_key` 컬럼이 먼저 필요하다. 이번 브랜치는 최신 develop에서 독립적으로 만들었으며 해당 MR의 구현·마이그레이션을 복사하지 않는다.
- `01-card-catalog.sql`만 설치한 DB는 대표 3종만 매핑한다. `02-card-catalog-ab20.sql`까지 설치한 DB는 17종을 매핑한다. `03` 실행이 `02` 실행을 대신하거나 자동 실행하지 않는다.
- Point Plan 1종은 보류, Deep Oil·원더카드 2.0 DAILY 2종은 제외 상태를 유지한다. 해당 상품이 DB에 있어도 이번 SQL로 이미지를 갱신하지 않는다.
- 실제 RDS 적용은 이번 작업에 포함하지 않는다. 적용 담당자가 243번 머지·스키마 적용 이후 별도로 실행한다.

## 파일과 출처

| 파일 | 내용 |
|---|---|
| `card-images.json` | 수집 20종의 이미지 키·SHA-256·정적 여부·기존 Seed 상품 식별값·적용/보류/제외 사유 |
| `../03-card-product-images.sql` | 적용 대상 17종 중 DB에 이미 있고 image_key가 NULL인 상품만 갱신 |
| `build_image_seed.py` | JSON 검증 및 SQL·대조 보고서 생성. Python 3 표준 라이브러리만 사용하며 DB 접속 없음 |
| `test_build_image_seed.py` | 중복·상품명·잘못된 키·삼성 정적 PNG·보류 대상 제외·재생성 검증 |
| `report.md` | 20종 정적 대조 결과와 DB 적용 시 미확인 항목 |

원본은 검증된 `card-image-key-mapping-cloudfront.json`이다. 원본 파일의 SHA-256을 JSON 상단에 기록했다.
14종의 DB 상품명은 기존 `catalog-ab20/db/card_product.csv`와 대조했고 대표 3종은 `01-card-catalog.sql`의 상품 식별값을 사용했다.
수집 ID(`card_id`)는 추적용이며 DB의 `card_product_id`로 사용하지 않는다. 매칭 기준은 카드사 코드·상품명·카드 종류의 정확한 일치다.
삼성 iD SIMPLE은 검증된 `static-front/*.png`를 선택하며 원본 GIF 정보는 출처로 보존한다.
CloudFront URL과 검증 시각은 참고 자료이며 DB에는 객체 키만 저장한다. 원격 이미지를 가져오거나 S3를 변경하는 실행은 없다.

## 생성과 파일 검증

저장소 루트에서 실행한다.

```powershell
python be/seed/card-images/build_image_seed.py
python -m unittest discover -s be/seed/card-images -p "test_*.py"
```

## DB 적용 담당자 실행 순서

1. 243번 머지와 해당 환경의 Flyway 적용 상태, `image_key` 컬럼 존재를 확인한다. SQL 실행기는 **오류가 발생하면 중단**하도록 설정한다.
2. DBeaver에서 대상 DB를 명시 선택하고 `SELECT DATABASE()`로 확인한다. 원격 접속 정보나 비밀번호를 이 파일에 넣지 않는다.
3. 필요한 상품 Seed가 이미 적재됐는지 확인한다. 기존 로컬 실행기의 기본 동작은 대표 3종이며 추가 14종을 의도 없이 넣지 않는다.
4. `03-card-product-images.sql`을 전체 스크립트로 실행한다. 스키마 확인 SELECT가 실패하면 컬럼을 임시 생성하지 말고 선행 마이그레이션 상태부터 확인한다.
5. 사전/사후 결과와 `updated_image_count`를 기록한다. **다른 키가 있는 상품은 자동 덮어쓰지 않는다.** 충돌은 수집 원본과 현행 담당자의 변경 의도를 확인한 뒤 별도 처리한다.

| 사전 상태 | 결과 |
|---|---|
| WILL_SET | 기존 상품이며 image_key가 NULL. 이미지 키만 채움 |
| ALREADY_MATCHED | 이미 같은 키. 재실행해도 변경 없음 |
| MISSING_PRODUCT | 상품 없음. 자동 INSERT하지 않음 |
| CONFLICT_EXISTING_KEY | 다른 키 또는 빈 문자열. 기존 값 보존, 담당자 확인 필요 |

정상 신규 적용은 대표 3종 DB에서 3건, 17종 DB에서 17건이다. 정상 재실행은 변경 0건이다.
기존 이미지가 있거나 상품이 일부만 있으면 변경 수가 줄 수 있으므로 총수만 보고 성공 판정하지 않는다.
사후 `MATCHED`와 누락·충돌 내역을 함께 확인한다. SQL은 트랜잭션에서 NULL 조건으로 갱신하므로
같은 작업이 겹쳐도 늦은 실행이 이미 설정된 키를 덮어쓰지 않는다. DDL·상품 INSERT·혜택 수정·키 교체는 수행하지 않는다.

## 격리 검증 범위

`CardProductImageSeedTest`는 MySQL 8.4.11에서 대표 3종/17종, DB PK와 무관한 식별값 매칭,
기존 이미지 충돌 보존, 재실행 0건, 보류 상품 유지, 상품의 다른 컬럼 보존을 확인한다.
243번 승인 대기 중에도 SQL 동작을 검증할 수 있도록 컬럼이 없는 격리 테스트 DB에만 테스트용 이미지 컬럼을 준비한다.
이 테스트용 준비는 운영 마이그레이션을 대신하지 않는다. 실제 컬럼 업그레이드는 MR !75의 별도 테스트 대상이다.

```powershell
cd be
.\gradlew.bat test --tests com.yamu.be.global.schema.CardProductImageSeedTest --tests com.yamu.be.global.schema.CardCatalogSeedTest --tests com.yamu.be.global.schema.CardCatalogAb20SeedTest
```

실제 RDS의 적재 상태·기존 이미지 충돌, 앱 화면 렌더링, 현재 CDN 가용성은 이 검증으로 확정하지 않는다.

2026-11-02 검증: develop `96fb66c` 기준에서 Python 파일 검증 9개와 격리 MySQL 8.4.11의
이미지 Seed 4개·기존 카탈로그 회귀 5개(합계 9개)가 통과했다. 두 실행 모두 실패·오류·생략 0개다.
Java 17 컴파일을 확인했고, 실제 RDS·S3 설정과 데이터는 변경하지 않았다.
