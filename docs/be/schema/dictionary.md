# 초기 스키마 컬럼 사전

사람과 AI가 함께 참조하는 30개 테이블의 저장 정의다. 개요·리뷰 순서는 [스키마 안내](README.md), 서비스 구현 책임과 미확인 사항은 [구현 지침](implementation.md)을 참고한다.

SQL 원본: [초기 생성](../../../be/src/main/resources/db/migration/V20261026_1__create_initial_schema.sql) → [결제별 한도 사용 테이블 이름 변경](../../../be/src/main/resources/db/migration/V20261027_1__rename_payment_limit_impact_to_usage.sql) → [금융망 사용자 ID 설명 수정](../../../be/src/main/resources/db/migration/V20261027_2__update_ssafy_member_id_comment.sql) → [모든 기기 서명 키 필수화](../../../be/src/main/resources/db/migration/V20261027_3__require_device_signing_keys.sql) → [현금흐름·구매 계획 보완](../../../be/src/main/resources/db/migration/V20261027_4__extend_cashflow_and_purchase_plans.sql) → [물품 코드·상품명·가맹점 업종 명칭 보완](../../../be/src/main/resources/db/migration/V20261027_5__separate_product_and_merchant_categories.sql) 순서로 적용한 최종 구조다. 초기 SQL은 이미 Merge되어 당시 이름과 설명을 보존한다. 작성일 2026-10-26, 문서 분리·컬럼명 정리 2026-10-27. 이슈 [S15P31E103-101](https://ssafy.atlassian.net/browse/S15P31E103-101), 후속 [S15P31E103-145](https://ssafy.atlassian.net/browse/S15P31E103-145).

컬럼·타입·NULL·기본값·키·제약은 이 문서에서 한 번만 관리한다. 정책의 상세 규칙은 각 절에 연결된 Notion 원본을 따른다. 아래 컬럼 이름과 제약 정의는 모든 마이그레이션 적용 후의 SQL 구조와 대조한다. 알림 JSON의 초기값·변경 규칙은 [구현 지침](implementation.md)의 해당 절을 참고한다.

## 전체 컬럼과 제약

### app_user — 사용자

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| user_id | `BIGINT NOT NULL AUTO_INCREMENT` | YAMU 사용자 번호 |
| name | `VARCHAR(100) NULL` | 본인인증 이름; 가입 진행 중 미확보 가능 |
| birth_date | `DATE NULL` | 본인인증 생년월일 |
| phone_enc | `VARBINARY(2048) NULL` | 정규화한 휴대폰 번호의 암호화 봉투 |
| phone_hash | `BINARY(32) NULL` | 휴대폰 조회용 keyed HMAC-SHA-256; 정규화 후 계산 |
| pin_hash | `VARCHAR(255) NULL` | 솔트 포함 PIN 비밀번호 해시; 원문 금지 |
| pin_updated_at | `TIMESTAMP(6) NULL` | UTC 마지막 PIN 설정·변경 시각 |
| identity_verified_at | `TIMESTAMP(6) NULL` | UTC 본인인증 완료 시각 |
| ssafy_member_id | `VARCHAR(255) NULL` | 서버 생성 금융망 userId; `y{랜덤 9자리}@yamu.app`(19자, SSAFY 40자 제한), 최초 생성 후 재사용, 실제 이메일 입력·메일 인증 없음. 중복 오류 E4002 시 다른 계정에 연결하지 않고 새 ID로 재생성 |
| ssafy_user_key | `VARBINARY(2048) NULL` | 금융망 userKey 암호화 봉투; 평문 금지 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | PENDING / ACTIVE / WITHDRAWN |
| deleted_at | `TIMESTAMP(6) NULL` | UTC 탈퇴 시각; 실제 개인정보 파기와 별개 |
| notification_settings | `JSON NULL` | 실적·잔고 위험·거래 알림의 켜기/끄기; 가입 시 세 값 모두 true, NULL·누락 키도 기본 켜짐으로 해석; 저장·검증은 서비스 책임 |
| forecast_settings | `JSON NULL` | 잔고예보 API 설정; 미설정 NULL, 계좌·수입 참조는 서비스에서 소유권 검증 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (user_id);
UNIQUE KEY uk_user_phone_birth (phone_hash, birth_date);
UNIQUE KEY uk_user_ssafy_member (ssafy_member_id);
CONSTRAINT ck_user_status CHECK (status IN ('PENDING','ACTIVE','WITHDRAWN'));
CONSTRAINT ck_user_active CHECK (status <> 'ACTIVE' OR (name IS NOT NULL AND birth_date IS NOT NULL AND phone_enc IS NOT NULL AND phone_hash IS NOT NULL AND pin_hash IS NOT NULL AND pin_updated_at IS NOT NULL AND identity_verified_at IS NOT NULL));
CONSTRAINT ck_user_withdrawn CHECK ((status = 'WITHDRAWN' AND deleted_at IS NOT NULL) OR (status <> 'WITHDRAWN' AND deleted_at IS NULL));
CONSTRAINT ck_app_user_version CHECK (version >= 0)
```

### user_device — 등록 기기

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| user_device_id | `BIGINT NOT NULL AUTO_INCREMENT` | 등록 기기 번호 |
| user_id | `BIGINT NOT NULL` | 기기 소유 사용자 |
| device_uuid | `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL` | 기기 등록 UUID |
| device_name | `VARCHAR(100) NULL` | 사용자에게 표시할 기기명 |
| platform | `VARCHAR(20) NOT NULL` | 기기 운영체제. 현재 Android만 지원하므로 ANDROID를 저장 |
| public_key | `TEXT NOT NULL` | 현재 서명 검증용 공개키; PIN 전용을 포함한 모든 기기 필수 |
| key_algorithm | `VARCHAR(32) NOT NULL` | 서명 검증 알고리즘; 모든 기기 필수 |
| biometric_enabled | `BOOLEAN NOT NULL DEFAULT FALSE` | 생체인증 사용 동의·설정 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | ACTIVE / LOCKED / REVOKED |
| failed_count | `INT NOT NULL DEFAULT 0` | 연속 인증 실패 횟수 |
| locked_until | `TIMESTAMP(6) NULL` | UTC 잠금 해제 예정 시각 |
| last_verified_at | `TIMESTAMP(6) NULL` | UTC 마지막 인증 성공 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (user_device_id);
UNIQUE KEY uk_device_owner_uuid (user_id, device_uuid);
UNIQUE KEY uk_device_owner (user_device_id, user_id);
CONSTRAINT fk_device_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT ck_device_status CHECK (status IN ('ACTIVE','LOCKED','REVOKED'));
CONSTRAINT ck_device_failed CHECK (failed_count >= 0);
CONSTRAINT ck_device_biometric CHECK (biometric_enabled IN (0,1));
CONSTRAINT ck_user_device_version CHECK (version >= 0)
```

### user_device_event — 기기 변경 이력

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| user_device_event_id | `BIGINT NOT NULL AUTO_INCREMENT` | 기기 이벤트 번호 |
| user_device_id | `BIGINT NOT NULL` | 이벤트 대상 기기 |
| event_type | `VARCHAR(32) NOT NULL` | 등록·키 교체·잠금·해제 등 이벤트 코드 |
| reason | `VARCHAR(255) NULL` | 민감값을 제거한 변경 사유 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (user_device_event_id);
KEY ix_device_event_time (user_device_id, created_at);
CONSTRAINT fk_device_event_device FOREIGN KEY (user_device_id) REFERENCES user_device (user_device_id)
```

### step_up_audit_log — 추가 인증 이력

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| step_up_audit_log_id | `BIGINT NOT NULL AUTO_INCREMENT` | 인증 기록 번호 |
| user_id | `BIGINT NOT NULL` | 인증 사용자 |
| user_device_id | `BIGINT NOT NULL` | 인증 기기; 필수 |
| auth_method | `VARCHAR(16) NOT NULL` | PIN / BIOMETRIC |
| purpose | `VARCHAR(32) NOT NULL` | LOGIN / APP_UNLOCK / BARCODE / ACCOUNT_LINK 등; 인증 명세 코드 |
| result | `VARCHAR(16) NOT NULL` | SUCCESS / FAILURE |
| fail_reason | `VARCHAR(100) NULL` | 원문 없이 저장하는 실패 코드 |
| challenge_id | `VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL` | 추적 식별자; challenge 원문 아님 |
| ip_address | `VARCHAR(45) CHARACTER SET ascii NULL` | IPv4 또는 IPv6 문자열 |
| user_agent | `VARCHAR(512) NULL` | 민감값을 제거한 요청 클라이언트 정보 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (step_up_audit_log_id);
UNIQUE KEY uk_audit_owner (step_up_audit_log_id, user_id);
KEY ix_audit_user_time (user_id, created_at);
KEY ix_audit_device_owner (user_device_id, user_id);
CONSTRAINT fk_audit_device_owner FOREIGN KEY (user_device_id, user_id) REFERENCES user_device (user_device_id, user_id);
CONSTRAINT ck_audit_method CHECK (auth_method IN ('PIN','BIOMETRIC'));
CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS','FAILURE'));
CONSTRAINT ck_audit_failure CHECK (result <> 'SUCCESS' OR fail_reason IS NULL)
```

### linked_account — 연결 계좌 참조

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| linked_account_id | `BIGINT NOT NULL AUTO_INCREMENT` | YAMU 연결 계좌 번호 |
| user_id | `BIGINT NOT NULL` | 계좌 소유 사용자 |
| bank_code | `VARCHAR(20) CHARACTER SET ascii NOT NULL` | 금융망 은행 코드; 문자열 |
| account_no_enc | `VARBINARY(2048) NOT NULL` | 금융망 계좌번호 암호화 봉투 |
| account_no_hash | `BINARY(32) NOT NULL` | 은행코드·정규화 계좌번호의 조회용 keyed HMAC |
| account_name | `VARCHAR(100) NULL` | 계좌 표시명 |
| account_alias | `VARCHAR(100) NULL` | 사용자가 지정한 계좌 별칭; 미지정 시 NULL |
| account_last4 | `CHAR(4) CHARACTER SET ascii NULL` | 마스킹 표시용 마지막 네 자리 |
| status | `VARCHAR(20) NOT NULL DEFAULT 'CONNECTED'` | CONNECTED / DISCONNECTED |
| step_up_audit_log_id | `BIGINT NULL` | 연결 시 사용한 PIN·생체 추가 인증 근거; 1원 인증과 구분 |
| connected_at | `TIMESTAMP(6) NOT NULL` | UTC 최근 연결 시각 |
| disconnected_at | `TIMESTAMP(6) NULL` | UTC 최근 연결 해제 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (linked_account_id);
UNIQUE KEY uk_account_identity (user_id, bank_code, account_no_hash);
UNIQUE KEY uk_account_owner (linked_account_id, user_id);
KEY ix_account_audit_owner (step_up_audit_log_id, user_id);
CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT fk_account_audit_owner FOREIGN KEY (step_up_audit_log_id, user_id) REFERENCES step_up_audit_log (step_up_audit_log_id, user_id);
CONSTRAINT ck_account_status CHECK (status IN ('CONNECTED','DISCONNECTED'));
CONSTRAINT ck_linked_account_version CHECK (version >= 0)
```

### card_product — 카드 상품

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| card_product_id | `BIGINT NOT NULL AUTO_INCREMENT` | YAMU 내부 상품 번호; 외부 상품 코드 아님 |
| issuer_code | `VARCHAR(32) NOT NULL` | YAMU 카드사 코드 |
| product_name | `VARCHAR(150) NOT NULL` | 세대·에디션을 포함해 구분한 상품명 |
| image_key | `VARCHAR(1024) NULL` | 카드 앞면 이미지 S3 객체 키. 미수집은 NULL. CDN URL은 API에서 조합 |
| card_type | `VARCHAR(16) NOT NULL` | CREDIT / CHECK |
| billing_cutoff_days | `SMALLINT NULL` | 결제일에서 뺄 일수; 신용카드 정책 자료로 설정 |
| terms_url | `VARCHAR(1000) NULL` | 원본 상품 약관 위치 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |

```sql
PRIMARY KEY (card_product_id);
UNIQUE KEY uk_product_catalog (issuer_code, product_name, card_type);
CONSTRAINT ck_product_type CHECK (card_type IN ('CREDIT','CHECK'));
CONSTRAINT ck_product_cutoff CHECK (billing_cutoff_days IS NULL OR billing_cutoff_days > 0)
```

### merchant — 브랜드 단위 가맹점

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| merchant_id | `BIGINT NOT NULL AUTO_INCREMENT` | YAMU 서버 발급 브랜드 단위 가맹점 번호 |
| merchant_name | `VARCHAR(150) NOT NULL` | 브랜드명; 예: 스타벅스 |
| brand_code | `VARCHAR(64) NOT NULL` | YAMU 내부 고유 브랜드 코드 |
| merchant_category_code | `VARCHAR(64) NOT NULL` | 가맹점 업종 코드; API merchantCategoryCode에 대응 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |

```sql
PRIMARY KEY (merchant_id);
UNIQUE KEY uk_merchant_brand (brand_code);
KEY ix_merchant_category (merchant_category_code)
```

### user_card — 사용자 보유 카드

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| user_card_id | `BIGINT NOT NULL AUTO_INCREMENT` | 보유 카드 번호 |
| user_id | `BIGINT NOT NULL` | 보유 사용자 |
| card_product_id | `BIGINT NOT NULL` | 카드 상품 |
| linked_account_id | `BIGINT NULL` | 출금 계좌; 같은 사용자 소유 |
| card_no_enc | `VARBINARY(2048) NOT NULL` | 모의 카드번호 암호화 봉투; CVC 저장 금지 |
| card_no_hash | `BINARY(32) NOT NULL` | 카드번호 조회·중복 방지용 keyed HMAC |
| card_last4 | `CHAR(4) CHARACTER SET ascii NOT NULL` | 마스킹 표시용 마지막 네 자리 |
| card_alias | `VARCHAR(100) NULL` | 사용자가 지정한 카드 별칭; 미지정 시 NULL |
| payment_day | `SMALLINT NULL` | 월 결제일; 신용카드에 사용 |
| status | `VARCHAR(20) NOT NULL` | PENDING: 서버가 준비한 사용자 등록 전 카드 / ACTIVE: 등록 완료, 추천·결제에 사용 / INACTIVE: 등록 유지, 추천에서 제외 / UNREGISTERED: 등록 해제, 과거 거래 이력 보존 |
| registered_at | `TIMESTAMP(6) NULL` | UTC 최초 사용자 등록 시각; 서버가 미리 준비한 등록 전 PENDING 카드는 NULL |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (user_card_id);
UNIQUE KEY uk_card_number (user_id, card_no_hash);
UNIQUE KEY uk_card_owner (user_card_id, user_id);
UNIQUE KEY uk_card_product (user_card_id, card_product_id);
UNIQUE KEY uk_card_owner_product (user_card_id, user_id, card_product_id);
KEY ix_card_account_owner (linked_account_id, user_id);
CONSTRAINT fk_card_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT fk_card_product FOREIGN KEY (card_product_id) REFERENCES card_product (card_product_id);
CONSTRAINT fk_card_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id);
CONSTRAINT ck_card_day CHECK (payment_day IS NULL OR payment_day BETWEEN 1 AND 31);
CONSTRAINT ck_user_card_version CHECK (version >= 0)
```

### card_benefit — 혜택 정책 버전

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| card_benefit_id | `BIGINT NOT NULL AUTO_INCREMENT` | 혜택 정책 버전 번호 |
| card_product_id | `BIGINT NOT NULL` | 대상 상품 |
| benefit_code | `VARCHAR(64) NOT NULL` | 버전 간 유지되는 YAMU 논리 혜택 코드 |
| policy_version | `SMALLINT NOT NULL` | 정책 버전 1~32767; JPA 락 버전과 별개 |
| benefit_name | `VARCHAR(150) NOT NULL` | 혜택 이름 |
| benefit_type | `VARCHAR(16) NOT NULL` | DISCOUNT / CASHBACK / POINT |
| calculation_type | `VARCHAR(16) NOT NULL` | RATE / FIXED |
| rate | `DECIMAL(9,6) NULL` | 0~1 비율; RATE일 때 필수 |
| fixed_value | `DECIMAL(19,6) NULL` | 정액 혜택값; 원화 또는 포인트 |
| value_unit | `VARCHAR(16) NOT NULL` | KRW / POINT |
| point_krw_rate | `DECIMAL(19,6) NULL` | 공식 포인트 원화 환산율; 미확인 NULL |
| application_timing | `VARCHAR(32) NULL` | AT_APPROVAL: 승인 시 즉시 할인 / AT_BILLING: 청구 시 할인 / AFTER_BILLING: 청구 후 캐시백 지급 / POINT_ACCRUAL: 포인트 적립; 약관 미확인 시 NULL |
| rounding_mode | `VARCHAR(16) NULL` | DOWN / HALF_UP / HALF_EVEN / UP; 약관 미확인 NULL |
| rounding_unit | `SMALLINT NULL` | 절사·반올림 결과 단위; 1이면 1원 또는 1포인트, 100이면 100원 또는 100포인트. 적립 기준금액과 구분; 약관 미확인 NULL |
| rounding_stage | `VARCHAR(32) NULL` | PER_TRANSACTION: 거래마다 소수점 처리 / AFTER_AGGREGATION: 여러 거래의 혜택을 합산한 뒤 소수점 처리; 합산 기간·대상은 별도 약관 조건으로 관리, 미확인 시 NULL |
| min_payment_amount | `BIGINT NOT NULL DEFAULT 0` | 최소 결제 금액 |
| min_performance_amount | `BIGINT NOT NULL DEFAULT 0` | 최소 전월 실적 |
| valid_from | `DATE NULL` | 공식 적용 시작일; 한국 날짜, 시작일 포함. 확인하지 못했다면 NULL, 등록일 등 임의 날짜로 대체하지 않음 |
| valid_until | `DATE NULL` | 공식 적용 종료일; 한국 날짜, 종료일 미포함. 종료일 미확인·미정은 NULL이며 무기한 유효를 증명하는 값은 아님 |
| conditions_json | `JSON NULL` | 요일·시간 추가 조건. 허용 키 weekdays / startTime / endTime. 한국 시각, 시작 포함·끝 미포함, 자정 넘김 지원. 아래 정책에 따라 서버가 등록·계산 검증 |
| terms_url | `VARCHAR(1000) NULL` | 이 버전의 원본 약관 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (card_benefit_id);
UNIQUE KEY uk_benefit_version (card_product_id, benefit_code, policy_version);
UNIQUE KEY uk_benefit_product (card_benefit_id, card_product_id);
KEY ix_benefit_effective (card_product_id, valid_from, valid_until);
CONSTRAINT fk_benefit_product FOREIGN KEY (card_product_id) REFERENCES card_product (card_product_id);
CONSTRAINT ck_benefit_version CHECK (policy_version > 0);
CONSTRAINT ck_benefit_type CHECK (benefit_type IN ('DISCOUNT','CASHBACK','POINT'));
CONSTRAINT ck_benefit_calc CHECK ((calculation_type = 'RATE' AND rate IS NOT NULL AND rate BETWEEN 0 AND 1 AND fixed_value IS NULL) OR (calculation_type = 'FIXED' AND fixed_value IS NOT NULL AND fixed_value >= 0 AND rate IS NULL));
CONSTRAINT ck_benefit_unit CHECK (value_unit IN ('KRW','POINT'));
CONSTRAINT ck_benefit_exchange CHECK (point_krw_rate IS NULL OR point_krw_rate > 0);
CONSTRAINT ck_benefit_rounding CHECK (rounding_mode IS NULL OR rounding_mode IN ('DOWN','HALF_UP','HALF_EVEN','UP'));
CONSTRAINT ck_benefit_rounding_unit CHECK (rounding_unit IS NULL OR rounding_unit > 0);
CONSTRAINT ck_benefit_rounding_stage CHECK (rounding_stage IS NULL OR rounding_stage IN ('PER_TRANSACTION','AFTER_AGGREGATION'));
CONSTRAINT ck_benefit_amounts CHECK (min_payment_amount >= 0 AND min_performance_amount >= 0);
CONSTRAINT ck_benefit_dates CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from)
```

#### 추가 조건 JSON 정책 — 2026-10-27

허용 키는 아래 세 개로 고정한다. 카드 데이터를 넣다가 새로운 조건이 필요해지면 허용 키·값 형식·판정 규칙을 먼저 문서화하고 검증·계산 로직을 함께 추가한다. 임의의 키나 실행 가능한 수식은 저장하지 않는다.

| 키 | 허용 값 | 계산 규칙 |
|---|---|---|
| weekdays | MON / TUE / WED / THU / FRI / SAT / SUN의 중복 없는 비어 있지 않은 배열 | 해당 요일에만 적용. 생략하면 요일 제한 없음 |
| startTime | 00:00~23:59의 HH:mm 문자열 | 시간 구간의 시작, 포함. endTime과 함께 지정 |
| endTime | 00:00~23:59의 HH:mm 문자열 | 시간 구간의 끝, 미포함. startTime과 함께 지정 |

- 조건은 결제 요청·승인 구현에서 확정한 거래 발생 시각을 Asia/Seoul로 변환해 검사한다. 승인 확정 시각 등 어떤 이벤트를 약관상 거래 발생으로 볼지는 결제 기능의 기존 기준을 따르며, 서버의 현재 시각으로 과거 거래를 다시 판정하지 않는다.
- 요일과 시간이 모두 있으면 둘 다 충족해야 한다. 요일 배열의 여러 요일 중 하나가 맞으면 요일 조건을 충족한다. NULL 또는 빈 객체 {}는 이 JSON에 추가 요일·시간 제한이 없다는 뜻이다. 미조사 약관을 조건 없음으로 임의 확정하는 용도로 사용하지 않는다.
- startTime < endTime이면 같은 날의 구간이다. 예: 14:00~18:00은 14:00 포함, 18:00 제외다. 두 시간 키를 모두 생략하면 시간 제한 없음이다.
- startTime > endTime이면 자정을 넘는 구간이다. 요일은 구간이 시작한 날짜로 판단한다. 예: weekdays=[FRI], 22:00~02:00이면 금요일 22:00부터 토요일 02:00 직전까지 적용된다. 토요일 01:00은 금요일 구간에 포함된다.
- 두 시간이 같은 입력은 24시간인지 빈 구간인지 모호하므로 거절한다. 하루 종일 적용하려면 시간 키를 모두 생략한다. 시간 키 하나만 존재하거나 null·잘못된 형식·빈 요일 배열·중복 요일·알 수 없는 키·잘못된 타입도 등록 시 거절한다.
- 브랜드·업종·채널·입점 시설 조건은 BENEFIT_TARGET과 거래 조건을 사용하며 이 JSON에 중복 저장하지 않는다. 이 JSON은 이미 별도 컬럼에 둔 최소 결제액·실적·반올림 조건도 대신하지 않는다.
- DB의 JSON 타입은 JSON 문법만 보장한다. 키 허용 목록·시간 경계·자정 넘김·요일 조합 검사는 서버 구현 책임이다. 조건은 당시 약관 버전에 보존하고, 계산 직전 남은 한도·횟수는 PAYMENT_LIMIT_USAGE.remaining_before, 사용한 전월 실적은 PAYMENT_TRANSACTION.performance_basis_amount에 보존한다. 현재 단계는 명세이며 해당 서비스 검증·계산 코드는 아직 구현하지 않았다.

```json
{
  "weekdays": ["FRI"],
  "startTime": "22:00",
  "endTime": "02:00"
}
```

위 예시는 자정 넘김 규칙을 설명하기 위한 값이며 특정 카드의 실제 약관으로 등록하는 데이터는 아니다. 요청 API의 필드명은 conditionsJson, DB 컬럼명은 conditions_json이다.

#### 소수점 처리 단계 조사

계산 단계는 실제 공식 안내에도 명시된다. 현대카드 [대한항공카드 the First Edition2 안내](https://www.hyundaicard.com/docfiles/resources/mo/html/carDtl/KALE2T4_MO.html)는 매출 건별 소수점 이하 반올림을, [대한항공카드 기존 상품 안내](https://www.hyundaicard.com/docfiles/resources/pc/html/carDtl/KALT2_PC.html)는 이용 건별 적립 마일리지를 합산한 뒤 소수점 이하 절상을 명시한다. 따라서 거래별 처리를 모든 상품의 공통 기본값으로 단정하지 않는다. 여러 거래의 금액·혜택을 모아 한 번 지급한다는 문구만으로 합산 후 소수점 처리라고 추정해서도 안 된다.

같은 절사·단위 1이어도 1.6포인트씩 두 거래라면 거래별 처리는 1+1=2포인트, 합산 후 처리는 3.2를 절사해 3포인트다. rounding_stage에는 확인한 계산 단계를 기록하고, 합산 기준 기간·대상·각 혜택별 계산 순서는 연결된 약관 버전에 보존한다. 2026-10-26 확정: PER_TRANSACTION / AFTER_AGGREGATION 두 코드만 CHECK로 허용하며, 실제 선택 카드의 단계 미확인 시 NULL을 유지한다.

### limit_group — 한도 정책 버전

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| limit_group_id | `BIGINT NOT NULL AUTO_INCREMENT` | 한도 그룹 버전 번호 |
| card_product_id | `BIGINT NOT NULL` | 대상 상품 |
| group_code | `VARCHAR(64) NOT NULL` | 담당자가 한도 의미에 맞춰 지정하는 내부 코드. 영문 대문자·밑줄 사용(예: LIFE_SHARED, CAFE_MONTHLY, CAFE_PER_TRANSACTION). 같은 카드 상품 안에서 서로 다른 한도 규칙을 구분하며 버전이 바뀌어도 유지. 변경 가능한 금액·버전 번호는 코드에 넣지 않음 |
| policy_version | `SMALLINT NOT NULL` | 한도 정책 버전 |
| group_name | `VARCHAR(150) NOT NULL` | 공유 한도 이름 |
| period_type | `VARCHAR(16) NOT NULL` | PER_TRANSACTION / DAILY / MONTHLY |
| limit_unit | `VARCHAR(16) NOT NULL` | KRW(원) / POINT(포인트) / COUNT(횟수). 혜택 종류가 아닌 한도 측정 단위이며 연결된 한도값·사용량·잔여량도 이 단위를 따름 |
| base_limit_value | `BIGINT NULL` | 기본 한도; 실적 구간별 값만 있으면 NULL |
| valid_from | `DATE NULL` | 한도 규칙의 공식 적용 시작일; 한국 날짜, 시작일 포함. 미확인 시 NULL |
| valid_until | `DATE NULL` | 한도 규칙의 공식 적용 종료일; 한국 날짜, 종료일 미포함. 종료일 미확인·미정은 NULL |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (limit_group_id);
UNIQUE KEY uk_limit_version (card_product_id, group_code, policy_version);
UNIQUE KEY uk_limit_product (limit_group_id, card_product_id);
CONSTRAINT fk_limit_product FOREIGN KEY (card_product_id) REFERENCES card_product (card_product_id);
CONSTRAINT ck_limit_version CHECK (policy_version > 0);
CONSTRAINT ck_limit_period CHECK (period_type IN ('PER_TRANSACTION','DAILY','MONTHLY'));
CONSTRAINT ck_limit_unit CHECK (limit_unit IN ('KRW','POINT','COUNT'));
CONSTRAINT ck_limit_value CHECK (base_limit_value IS NULL OR base_limit_value >= 0);
CONSTRAINT ck_limit_dates CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from)
```

### benefit_target — 혜택 적용·제외 대상

2026-11-03: `(benefit_target_id,card_benefit_id)` UNIQUE 추가. CONDITION 이외 행은 is_excluded=FALSE CHECK. PURCHASE_ITEM의 target_value 등록 여부는 서비스 검증이며 전용 FK를 달지 않는다. 대상의 적용/제외는 아래 경로 테이블에서 결정한다.

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| benefit_target_id | `BIGINT NOT NULL AUTO_INCREMENT` | 혜택 대상 번호 |
| card_benefit_id | `BIGINT NOT NULL` | 혜택 정책 버전 |
| target_type | `VARCHAR(16) NOT NULL` | ALL / BRAND / CATEGORY / CONDITION / PURCHASE_ITEM |
| merchant_id | `BIGINT NULL` | BRAND 대상의 브랜드 단위 MERCHANT 참조 |
| target_value | `VARCHAR(128) NULL` | 업종 또는 거래 조건 코드; 예: CHANNEL:ONLINE, FACILITY:DEPARTMENT_STORE |
| is_excluded | `BOOLEAN NOT NULL DEFAULT FALSE` | CONDITION의 제외 조건. 그 외 대상은 FALSE이고 경로의 제외 여부는 그룹에 저장 |
| target_key | `VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN target_type = 'BRAND' THEN CAST(merchant_id AS CHAR) WHEN target_type = 'ALL' THEN '*' ELSE target_value END) STORED` | NULL 중복을 피하는 대상 비교 키 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (benefit_target_id);
UNIQUE KEY uk_benefit_target (card_benefit_id, target_type, is_excluded, target_key);
CONSTRAINT fk_target_benefit FOREIGN KEY (card_benefit_id) REFERENCES card_benefit (card_benefit_id);
CONSTRAINT fk_target_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id);
CONSTRAINT ck_target_excluded CHECK (is_excluded IN (0,1));
CONSTRAINT ck_target_shape CHECK ((target_type = 'BRAND' AND merchant_id IS NOT NULL AND target_value IS NULL) OR (target_type = 'ALL' AND merchant_id IS NULL AND target_value IS NULL) OR (target_type IN ('CATEGORY','CONDITION','PURCHASE_ITEM') AND merchant_id IS NULL AND target_value IS NOT NULL))
```

### benefit_limit_link — 혜택과 공유 한도 연결

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| benefit_limit_link_id | `BIGINT NOT NULL AUTO_INCREMENT` | 혜택·한도 연결 번호 |
| card_product_id | `BIGINT NOT NULL` | 같은 상품 연결 검증용 |
| card_benefit_id | `BIGINT NOT NULL` | 혜택 정책 버전 |
| limit_group_id | `BIGINT NOT NULL` | 한도 그룹 버전 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (benefit_limit_link_id);
UNIQUE KEY uk_benefit_limit (card_benefit_id, limit_group_id);
KEY ix_benefit_link_product (card_benefit_id, card_product_id);
KEY ix_limit_link_product (limit_group_id, card_product_id);
CONSTRAINT fk_link_benefit_product FOREIGN KEY (card_benefit_id, card_product_id) REFERENCES card_benefit (card_benefit_id, card_product_id);
CONSTRAINT fk_link_limit_product FOREIGN KEY (limit_group_id, card_product_id) REFERENCES limit_group (limit_group_id, card_product_id)
```

### tier_limit — 실적 구간별 한도

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| tier_limit_id | `BIGINT NOT NULL AUTO_INCREMENT` | 실적 구간 한도 번호 |
| limit_group_id | `BIGINT NOT NULL` | 한도 그룹 버전 |
| min_performance_amount | `BIGINT NOT NULL` | 실적 하한; 포함 |
| max_performance_amount | `BIGINT NULL` | 실적 상한; 미포함, NULL 무제한 |
| limit_value | `BIGINT NOT NULL` | 한도값; 단위는 한도 그룹을 따름 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (tier_limit_id);
UNIQUE KEY uk_tier_lower (limit_group_id, min_performance_amount);
CONSTRAINT fk_tier_group FOREIGN KEY (limit_group_id) REFERENCES limit_group (limit_group_id);
CONSTRAINT ck_tier_range CHECK (min_performance_amount >= 0 AND (max_performance_amount IS NULL OR max_performance_amount > min_performance_amount));
CONSTRAINT ck_tier_value CHECK (limit_value >= 0)
```

### payment_transaction — 결제 요청·승인·취소

**결제 한 건당 한 행을 만들고, 요청·처리 중·승인·거절·취소에 따라 같은 행의 status와 결과 컬럼을 갱신한다. 요청용 행과 승인용 행을 따로 만드는 구조가 아니다.** 상태가 바뀔 때마다 새 행을 추가하는 이력 테이블도 아니다.

| 상황 예시 | status | 이 테이블의 fin_transaction_no |
|---|---|---|
| POS 결제 요청 접수 | REQUESTED | NULL |
| 체크카드 출금 처리 중·결과 확인 중 | PROCESSING | 성공 거래 번호 확인 전에는 NULL |
| 체크카드 출금 성공 후 승인 완료 | APPROVED | 금융망이 반환한 출금 거래 번호 |
| 체크카드 잔액 부족으로 출금 실패 확정 | DECLINED | NULL |
| 체크카드 출금 결과 불명 뒤 유예가 지나도 거래내역에 태그가 없어 거절(WITHDRAWAL_NOT_CONFIRMED, 임시) | DECLINED, 마감이 지났으면 EXPIRED | NULL |
| 위 거절 뒤 같은 요청 번호의 출금이 늦게 확인되어 승인으로 바로잡음(임시) | APPROVED | 거래내역에서 찾은 출금 거래 번호 |
| 금융망에 출금 요청을 보내지 못해 승인 전으로 되돌림(임시) | REQUESTED | NULL |
| 신용카드 일시불·할부 승인 완료 | APPROVED | NULL. 승인 때 계좌에서 출금하지 않음 |

fin_transaction_no는 상태 코드가 아니라 **이 결제로 발생한 계좌 출금의 거래 번호**다. NULL만 보고 결제 실패로 판단하지 않고 status를 함께 확인한다. 출금 응답을 받지 못한 경우도 바로 거절로 확정하지 않고 결과를 확인한다. 체크카드 결제를 나중에 취소해도 원 출금 번호는 보존하며 환급 거래는 REFUND에서 관리한다. 신용카드는 일시불·할부 모두 결제일에 CARD_BILL 기준으로 출금하고, 그 출금 거래 번호는 CARD_BILL에 저장한다. 가맹점 정산을 기록하는 테이블은 아니다.

출금 결과 불명 처리(임시, Task 5.2.3 · [S15P31E103-199](https://ssafy.atlassian.net/browse/S15P31E103-199)): 결과를 모르는 PROCESSING은 fin_request_no의 태그로 거래내역을 찾아 fin_request_amount와 금액이 같으면 APPROVED, 유예가 지나도 없으면 WITHDRAWAL_NOT_CONFIRMED로 DECLINED(마감이 지났으면 EXPIRED)로 확정한다. 금액이 다르거나 태그가 둘 이상이면 PROCESSING에 두고 운영이 확인한다. 기존 흐름에 없는 전이 두 가지를 임시로 쓴다. PROCESSING → REQUESTED는 금융망에 아무것도 보내지 못했을 때만, DECLINED·EXPIRED → APPROVED는 WITHDRAWAL_NOT_CONFIRMED이고 같은 fin_request_no의 출금이 늦게 확인됐을 때만이다. 재승인을 시작하면 앞 시도의 fin_request_no·fin_request_amount·fin_transaction_no를 지우고 새로 받는다(앞 번호는 Redis 시도 기록에 남는다).

시설 코드와 혜택 적용 규칙의 기준 문서는 [FR-014의 입점 시설 코드 정책](https://app.notion.com/p/e0431720f35882188d5101733ebd9269)이다. 이 절은 DB 저장값을 설명한다.

2026-10-27 확정: GENERAL(일반 매장), DEPARTMENT_STORE(백화점), DISCOUNT_STORE(할인점·대형마트), DUTY_FREE(면세점), OUTLET(아울렛), SHOPPING_MALL(쇼핑몰), AIRPORT(공항), HOTEL(호텔), RESORT(리조트), TRAIN_STATION(역사). NULL은 미확인이다. GENERAL은 해당 시설에 입점하지 않은 것으로 확인한 경우에만 사용한다.

제외 조건은 BENEFIT_TARGET에 CONDITION / FACILITY:시설코드 / is_excluded=true로 저장한다. 서버는 시설 코드의 허용 목록을 검증하고 실제 거래의 facility_type과 비교한다. 시설 코드가 존재한다는 이유만으로 모든 카드에서 제외하지 않으며 혜택별 약관을 따른다. VARCHAR 자체에 허용 목록 CHECK는 없고, 서비스 검증·판정 로직은 아직 구현하지 않았다.

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| payment_transaction_id | `BIGINT NOT NULL AUTO_INCREMENT` | 결제 거래 번호 |
| user_id | `BIGINT NOT NULL` | 결제 요청 사용자 |
| user_card_id | `BIGINT NULL` | 승인 카드; 선택 전 요청에는 NULL |
| card_product_id | `BIGINT NULL` | 선택 카드의 상품; 교차 상품 적용 방지 |
| merchant_id | `BIGINT NOT NULL` | 결제한 브랜드 단위 가맹점 |
| payment_channel | `VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'` | ONLINE / OFFLINE / UNKNOWN; 서버가 확인한 거래 채널 |
| facility_type | `VARCHAR(32) NULL` | 위에서 확정한 입점 시설 코드 10종 중 하나; 미확인 NULL, GENERAL로 대체 금지 |
| merchant_context_snapshot | `JSON NULL` | 서버 확인 POS·브랜드 코드·업종·거래 조건의 당시 근거; 사용자 입력으로 확정 금지 |
| request_key | `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL` | POS 생성 UUID; 재시도 때 같은 값 |
| request_fingerprint | `BINARY(32) NOT NULL` | 최초 요청 불변 내용의 SHA-256; 중복 키 요청 비교 |
| source | `VARCHAR(16) NOT NULL` | YAMU / SEED |
| status | `VARCHAR(24) NOT NULL` | 결제 처리 상태 코드; REQUESTED 등 API 계약 |
| requested_amount | `BIGINT NOT NULL` | 최초 요청 금액; 양수 |
| approved_amount | `BIGINT NULL` | 확정 모의 승인 금액 = requested_amount − 즉시할인(체크카드 출금액·신용카드 청구 원금 합계). 청구할인·캐시백·적립은 빼지 않음; 전액 할인 시 0 가능 |
| installment_months | `SMALLINT NULL` | 일시불은 1, 할부는 총 개월 수(3개월 할부는 3); 현재 회차 번호가 아님. 지원 개월은 서비스 검증 |
| recognized_performance_amount | `BIGINT NULL` | 승인 당시 실적 인정액; 미확정은 NULL |
| performance_basis_amount | `BIGINT NULL` | 혜택 계산에 사용한 전월 실적 인정액(원). 실제 0원은 0, 계산 전·실적 조건 미사용은 NULL. 이번 결제로 새로 쌓이는 실적과 구분 |
| performance_month | `DATE NULL` | 이 결제의 실적을 합산할 월(한국 기준). 2026년 10월이면 2026-10-01로 저장하며, 1일은 월 표시용이지 실제 결제일이 아님. 미확정은 NULL |
| requested_at | `TIMESTAMP(6) NOT NULL` | UTC 요청 시각 |
| approved_at | `TIMESTAMP(6) NULL` | UTC 승인 시각 |
| canceled_at | `TIMESTAMP(6) NULL` | UTC 전체 취소 시각 |
| performance_reversed_at | `TIMESTAMP(6) NULL` | UTC 실적 복구 완료 시각; 서비스에서 1회 처리 |
| failure_code | `VARCHAR(64) NULL` | 승인 거절 시 API failure.code와 같은 값 저장: INSUFFICIENT_BALANCE(잔액 부족) / CREDIT_LIMIT_EXCEEDED(신용 한도 초과) / CARD_SUSPENDED(카드 정지) / UNPAID_BILL(미납) / ISSUER_DECLINED(모의 승인 거절) / ACCOUNT_UNAVAILABLE(출금 계좌 없음·해지, 임시) / WITHDRAWAL_NOT_CONFIRMED(출금 결과 불명 뒤 거래내역에 없음, 임시). 거절 사유가 없으면 NULL. 서버에서 허용값 검증 |
| fin_request_no | `CHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL` | SSAFY 요청 Header.institutionTransactionUniqueNo. 서버가 YYYYMMDD + HHMMSS + 일련번호 6자리로 생성; 아래 채번 규칙 참조. 요청 전 NULL |
| fin_request_amount | `BIGINT NULL` | 이번 시도의 체크카드 출금 요청액(결제 금액 − 즉시할인). fin_request_no와 같은 트랜잭션에서 저장하고 재승인·되돌리기 때 함께 지운다. 출금 결과 불명 시 거래내역 금액과 비교한다. 출금 요청 전·신용카드·0원 승인은 NULL; S15P31E103-292 이전 행도 NULL이라 requested_amount로 비교 |
| fin_transaction_no | `VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL` | 체크카드 출금 성공 시 금융망이 반환한 거래 번호; 상태 코드가 아님. 신용카드는 승인 시 NULL이며 결제일 출금 번호는 CARD_BILL에 저장 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |
| spending_class | `VARCHAR(16) NULL` | LIVING / LARGE / EXCLUDED; 미분류 NULL, 실적 인정액과 별개 |
| spending_class_source | `VARCHAR(16) NULL` | AUTO / USER; 미분류 NULL, 사용자 수정 USER는 자동 재분류로 덮어쓰지 않음 |
| memo | `VARCHAR(100) NULL` | 사용자 결제 메모(한 줄); API는 줄바꿈·탭을 공백으로 바꾸고 다른 제어문자는 거절, 빈 문자열 요청은 삭제로 처리 |
| memo_updated_at | `TIMESTAMP(6) NULL` | UTC 메모를 남기거나 지운 마지막 시각; 메모 API만 바꿈, 고친 적 없으면 NULL (S15P31E103-301, V20261106_1) |

```sql
PRIMARY KEY (payment_transaction_id);
UNIQUE KEY uk_payment_request (request_key);
UNIQUE KEY uk_payment_product (payment_transaction_id, card_product_id);
UNIQUE KEY uk_payment_card_owner (payment_transaction_id, user_id, user_card_id);
KEY ix_payment_owner_time (user_id, requested_at);
KEY ix_payment_card_time (user_card_id, approved_at);
KEY ix_payment_card_owner_product (user_card_id, user_id, card_product_id);
CONSTRAINT fk_payment_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT fk_payment_card_owner_product FOREIGN KEY (user_card_id, user_id, card_product_id) REFERENCES user_card (user_card_id, user_id, card_product_id);
CONSTRAINT fk_payment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id);
CONSTRAINT ck_payment_channel CHECK (payment_channel IN ('ONLINE','OFFLINE','UNKNOWN'));
CONSTRAINT ck_payment_card_pair CHECK ((user_card_id IS NULL AND card_product_id IS NULL) OR (user_card_id IS NOT NULL AND card_product_id IS NOT NULL));
CONSTRAINT ck_payment_amounts CHECK (requested_amount > 0 AND (approved_amount IS NULL OR approved_amount >= 0) AND (recognized_performance_amount IS NULL OR recognized_performance_amount >= 0));
CONSTRAINT ck_payment_installment CHECK (installment_months IS NULL OR installment_months > 0);
CONSTRAINT ck_payment_fin_request_amount CHECK (fin_request_amount IS NULL OR fin_request_amount > 0);
CONSTRAINT ck_payment_performance_basis CHECK (performance_basis_amount IS NULL OR performance_basis_amount >= 0);
CONSTRAINT ck_payment_month CHECK (performance_month IS NULL OR DAYOFMONTH(performance_month) = 1);
CONSTRAINT ck_payment_source CHECK (source IN ('YAMU','SEED'));
CONSTRAINT ck_payment_approval CHECK (approved_at IS NULL OR (user_card_id IS NOT NULL AND approved_amount IS NOT NULL AND recognized_performance_amount IS NOT NULL AND performance_month IS NOT NULL AND installment_months IS NOT NULL));
CONSTRAINT ck_payment_cancellation CHECK (canceled_at IS NULL OR approved_at IS NOT NULL);
CONSTRAINT ck_payment_transaction_version CHECK (version >= 0);
UNIQUE KEY uk_payment_owner (payment_transaction_id, user_id);
CONSTRAINT ck_payment_spending_class CHECK (spending_class IS NULL OR spending_class IN ('LIVING','LARGE','EXCLUDED'));
CONSTRAINT ck_payment_spending_pair CHECK ((spending_class IS NULL AND spending_class_source IS NULL) OR (spending_class IS NOT NULL AND spending_class_source IS NOT NULL AND spending_class_source IN ('AUTO','USER')));
```

#### 금융망 요청 번호 채번 규칙

- 기준 문서: Notion → API 명세서 → V2 → API 목록 → [결제 승인 요청 (카드 확정)](https://app.notion.com/p/6ca31720f35882e9826c81de0fbe7309) → 본문 「금융망 요청 번호 채번 규칙」.
- fin_request_no는 Header.institutionTransactionUniqueNo다. 서버가 `YYYYMMDD`(8자리) + `HHMMSS`(6자리) + 일련번호(6자리)로 만든다. 예: `20261027143005000001`. UUID·난수 방식을 사용하지 않는다.
- 날짜·시각은 Asia/Seoul 기준의 같은 시각으로 만들고 요청 Header의 transmissionDate·transmissionTime과 맞춘다. 같은 금융망 연동 계정의 모든 서버·API가 공유하는 Redis INCR로 해당 초의 순번을 발급하고 6자리로 0을 채운다. 999999 초과 또는 카운터 유실·초기화로 중복 방지를 보장할 수 없으면 번호를 재사용하지 않고 호출을 보류한다.
- 금융망 API를 새로 호출할 때마다 새 번호를 발급한다. POS의 Idempotency-Key와는 다르다. 출금 결과 불명 시 기존 거래 상태를 먼저 확인하며 새 번호를 만들었다고 출금을 반복하지 않는다. 조회 요청 번호로 원 출금·입금 요청의 근거를 덮어쓰지 않는다.
- payment_transaction·card_bill·refund의 fin_request_no에 공통 적용한다. DB는 CHAR(20), 서버는 정확히 20자리 숫자인지 검증한다. 이 단일 컬럼이 모든 API 호출 이력을 저장하는 것은 아니며 Redis 채번 서비스는 아직 구현하지 않았다.

### payment_benefit — 결제별 실제 적용 혜택 결과

사용자가 실제로 받은 할인·캐시백·포인트 결과를 혜택별로 저장한다. 정책은 card_benefit_id로 연결된 당시 버전을 확인한다. 한도에서 차감한 금액·횟수는 PAYMENT_LIMIT_USAGE에 저장한다.

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| payment_benefit_id | `BIGINT NOT NULL AUTO_INCREMENT` | 혜택 적용 결과 번호 |
| payment_transaction_id | `BIGINT NOT NULL` | 결제 거래 |
| card_product_id | `BIGINT NOT NULL` | 거래·혜택 상품 일치 검증용 |
| card_benefit_id | `BIGINT NOT NULL` | 적용한 혜택 정책 버전 |
| benefit_value | `DECIMAL(19,6) NOT NULL` | 실제 적용한 할인·캐시백·적립 결과; 원화 또는 포인트. 한도 소모량이 아님 |
| value_unit | `VARCHAR(16) NOT NULL` | KRW / POINT |
| krw_amount | `BIGINT NULL` | 확인 가능한 원화 혜택 금액; 미환산 NULL |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (payment_benefit_id);
UNIQUE KEY uk_payment_benefit (payment_transaction_id, card_benefit_id);
KEY ix_applied_payment_product (payment_transaction_id, card_product_id);
KEY ix_applied_benefit_product (card_benefit_id, card_product_id);
CONSTRAINT fk_applied_payment_product FOREIGN KEY (payment_transaction_id, card_product_id) REFERENCES payment_transaction (payment_transaction_id, card_product_id);
CONSTRAINT fk_applied_benefit_product FOREIGN KEY (card_benefit_id, card_product_id) REFERENCES card_benefit (card_benefit_id, card_product_id);
CONSTRAINT ck_applied_values CHECK (benefit_value >= 0 AND (krw_amount IS NULL OR krw_amount >= 0));
CONSTRAINT ck_applied_unit CHECK (value_unit IN ('KRW','POINT'))
```

#### 당시 계산 근거의 저장 위치

기준 문서: Notion → 요구사항 정의서 → V2 → 요구사항 정의서 V2 → [DEC-005](https://app.notion.com/p/d7531720f358838e99690172f32e130d) → 본문 「혜택 결과·한도 사용·당시 계산 근거」. calculation_snapshot JSON은 제거하고 아래 두 일반 컬럼으로 저장한다.

- 계산 직전 남은 한도·횟수: PAYMENT_LIMIT_USAGE.remaining_before.
- 계산에 사용한 전월 실적 인정액: PAYMENT_TRANSACTION.performance_basis_amount. 실제 0원과 미확정·미사용 NULL을 구분하며, 필요한 실적이 미확인인 채로 확정 계산하지 않는다. 같은 결제의 여러 혜택에서 공통으로 참조한다.
- 당시 값은 실제 승인에 반영한 계산에서 확보하고 이후 집계가 바뀌거나 취소돼도 덮어쓰지 않는다. 비율·반올림·적용 시점은 보존된 약관·한도 버전으로 확인한다.

### payment_limit_usage — 결제별 한도 사용 내역과 취소 복구 기록

이전 이름 payment_limit_impact를 후속 마이그레이션으로 변경했다. PK도 payment_limit_usage_id로 변경하며 기존 행과 사용량·복구 시각은 보존한다.

이 결제가 한도 그룹별로 얼마·몇 회를 차감했는지 저장한다. 취소하면 consumed_value만큼 한 번 복구하고 restored_at을 기록하며, remaining_before 전체를 복구하는 것이 아니다. 여러 혜택이 같은 한도를 공유하면 같은 결제·한도 그룹의 사용량을 한 행에 합산한다.

예: 실제 할인 300원은 PAYMENT_BENEFIT에 한 번 기록한다. 금액 한도 300원과 횟수 한도 1회를 함께 사용했다면 PAYMENT_LIMIT_USAGE에는 서로 다른 한도 그룹으로 두 행을 남긴다. 이 두 행을 합산해 실제 할인액으로 사용하지 않는다.

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| payment_limit_usage_id | `BIGINT NOT NULL AUTO_INCREMENT` | 한도 사용 근거 번호 |
| payment_transaction_id | `BIGINT NOT NULL` | 결제 거래 |
| card_product_id | `BIGINT NOT NULL` | 거래·한도 상품 일치 검증용 |
| limit_group_id | `BIGINT NOT NULL` | 결제 당시 한도 그룹 버전 |
| remaining_before | `BIGINT NOT NULL` | 이 결제가 소모하기 직전 남은 한도 금액·횟수; 단위는 연결된 한도 그룹 |
| consumed_value | `BIGINT NOT NULL` | 이 결제로 차감한 한도 금액·횟수. 취소 시 이 값만 복구하며 실제 혜택 결과와 구분 |
| usage_date | `DATE NOT NULL` | 한국 기준 사용일; 일별 검사 |
| usage_month | `DATE NOT NULL` | 한국 기준 사용 월의 1일 |
| restored_at | `TIMESTAMP(6) NULL` | UTC 취소 복구 완료 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (payment_limit_usage_id);
UNIQUE KEY uk_payment_limit (payment_transaction_id, limit_group_id);
KEY ix_impact_daily (limit_group_id, usage_date, payment_transaction_id);
KEY ix_impact_payment_product (payment_transaction_id, card_product_id);
KEY ix_impact_limit_product (limit_group_id, card_product_id);
CONSTRAINT fk_impact_payment_product FOREIGN KEY (payment_transaction_id, card_product_id) REFERENCES payment_transaction (payment_transaction_id, card_product_id);
CONSTRAINT fk_impact_limit_product FOREIGN KEY (limit_group_id, card_product_id) REFERENCES limit_group (limit_group_id, card_product_id);
CONSTRAINT ck_impact_value CHECK (remaining_before >= 0 AND consumed_value >= 0 AND consumed_value <= remaining_before);
CONSTRAINT ck_impact_month CHECK (DAYOFMONTH(usage_month) = 1 AND YEAR(usage_month) = YEAR(usage_date) AND MONTH(usage_month) = MONTH(usage_date));
CONSTRAINT ck_payment_limit_usage_version CHECK (version >= 0)
```

### card_monthly_performance — 카드별 월간 실적

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| card_monthly_performance_id | `BIGINT NOT NULL AUTO_INCREMENT` | 월간 실적 번호 |
| user_card_id | `BIGINT NOT NULL` | 보유 카드 |
| performance_month | `DATE NOT NULL` | 실적 집계 대상 월(한국 기준). 2026년 10월이면 2026-10-01로 저장하며, 1일은 월 표시용이지 실제 결제일이 아님 |
| recognized_amount | `BIGINT NOT NULL` | 확정된 월간 실적 합계. 신규 카드·확인된 무거래는 0. seed 누락·초기화 실패는 등록 실패로 처리 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (card_monthly_performance_id);
UNIQUE KEY uk_performance_card_month (user_card_id, performance_month);
CONSTRAINT fk_performance_card FOREIGN KEY (user_card_id) REFERENCES user_card (user_card_id);
CONSTRAINT ck_performance_month CHECK (DAYOFMONTH(performance_month) = 1);
CONSTRAINT ck_performance_amount CHECK (recognized_amount >= 0);
CONSTRAINT ck_card_monthly_performance_version CHECK (version >= 0)
```

### monthly_limit_usage — 카드별 월간 한도 사용량

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| monthly_limit_usage_id | `BIGINT NOT NULL AUTO_INCREMENT` | 월간 한도 사용 번호 |
| user_card_id | `BIGINT NOT NULL` | 보유 카드 |
| card_product_id | `BIGINT NOT NULL` | 카드·한도 상품 일치 검증용 |
| limit_group_id | `BIGINT NOT NULL` | 한도 그룹 버전 |
| usage_month | `DATE NOT NULL` | 한국 기준 월의 1일 |
| used_value | `BIGINT NOT NULL` | 확정된 한도 사용량; 금액 또는 횟수. 신규 카드·확인된 미사용은 0 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (monthly_limit_usage_id);
UNIQUE KEY uk_usage_card_limit_month (user_card_id, limit_group_id, usage_month);
KEY ix_usage_card_product (user_card_id, card_product_id);
KEY ix_usage_limit_product (limit_group_id, card_product_id);
CONSTRAINT fk_usage_card_product FOREIGN KEY (user_card_id, card_product_id) REFERENCES user_card (user_card_id, card_product_id);
CONSTRAINT fk_usage_limit_product FOREIGN KEY (limit_group_id, card_product_id) REFERENCES limit_group (limit_group_id, card_product_id);
CONSTRAINT ck_usage_month CHECK (DAYOFMONTH(usage_month) = 1);
CONSTRAINT ck_usage_value CHECK (used_value >= 0);
CONSTRAINT ck_monthly_limit_usage_version CHECK (version >= 0)
```

### card_bill — 카드 월별 청구서

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| card_bill_id | `BIGINT NOT NULL AUTO_INCREMENT` | 카드 청구서 번호 |
| user_id | `BIGINT NOT NULL` | 청구 사용자 |
| user_card_id | `BIGINT NOT NULL` | 청구 카드 |
| linked_account_id | `BIGINT NULL` | 청구 확정 당시 출금 계좌 |
| billing_month | `DATE NOT NULL` | 한국 기준 청구 월의 1일 |
| period_start | `DATE NOT NULL` | 이용기간 시작; 포함 |
| period_end | `DATE NOT NULL` | 이용기간 마감; 포함 |
| withdrawal_date | `DATE NOT NULL` | 한국 업무 날짜 출금 예정일 |
| status | `VARCHAR(24) NOT NULL` | 청구·출금 처리 상태 코드 |
| confirmed_amount | `BIGINT NULL` | 확정 원본 청구액; 확정 후 덮어쓰지 않음 |
| paid_amount | `BIGINT NOT NULL DEFAULT 0` | 확인된 출금 금액 |
| canceled_unpaid_principal | `BIGINT NOT NULL DEFAULT 0` | 취소로 납부 대상에서 제외한 미납 원금 |
| confirmed_at | `TIMESTAMP(6) NULL` | UTC 청구 확정 시각 |
| paid_at | `TIMESTAMP(6) NULL` | UTC 출금 성공 확인 시각 |
| failed_at | `TIMESTAMP(6) NULL` | UTC 출금 실패 확인 시각 |
| fin_request_no | `CHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL` | 은행 출금 요청의 기관거래고유번호. YYYYMMDD + HHMMSS + 일련번호 6자리; payment_transaction 절의 공통 채번 규칙 적용 |
| fin_transaction_no | `VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL` | 확인한 은행 출금 거래 참조 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (card_bill_id);
UNIQUE KEY uk_bill_card_month (user_card_id, billing_month);
UNIQUE KEY uk_bill_card_owner (card_bill_id, user_id, user_card_id);
KEY ix_bill_due (status, withdrawal_date);
KEY ix_bill_card_owner (user_card_id, user_id);
KEY ix_bill_account_owner (linked_account_id, user_id);
CONSTRAINT fk_bill_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id);
CONSTRAINT fk_bill_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id);
CONSTRAINT ck_bill_month CHECK (DAYOFMONTH(billing_month) = 1);
CONSTRAINT ck_bill_period CHECK (period_end >= period_start);
CONSTRAINT ck_bill_amounts CHECK ((confirmed_amount IS NULL OR confirmed_amount >= 0) AND paid_amount >= 0 AND canceled_unpaid_principal >= 0 AND (confirmed_amount IS NULL OR canceled_unpaid_principal <= confirmed_amount));
CONSTRAINT ck_bill_confirmed CHECK (confirmed_at IS NULL OR (confirmed_amount IS NOT NULL AND linked_account_id IS NOT NULL));
CONSTRAINT ck_bill_paid CHECK (paid_at IS NULL OR (confirmed_at IS NOT NULL AND linked_account_id IS NOT NULL));
CONSTRAINT ck_card_bill_version CHECK (version >= 0)
```

### bill_item — 일시불·할부 회차 청구

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| bill_item_id | `BIGINT NOT NULL AUTO_INCREMENT` | 청구 회차 번호 |
| payment_transaction_id | `BIGINT NOT NULL` | 원결제; 전체 취소 단위 |
| user_id | `BIGINT NOT NULL` | 원결제 사용자; 청구서 일치 검증용 |
| user_card_id | `BIGINT NOT NULL` | 원결제 카드; 청구서 일치 검증용 |
| card_bill_id | `BIGINT NULL` | 배정 청구서; 배정 전 NULL |
| installment_no | `SMALLINT NOT NULL` | 일시불 1, 할부 1부터 시작 |
| billing_month | `DATE NOT NULL` | 예정 청구 월의 1일 |
| principal_amount | `BIGINT NOT NULL` | 회차 원금; 나머지는 마지막 회차 |
| fee_amount | `BIGINT NULL` | 회차 수수료; 미산정 NULL, 무이자 확정 0 |
| billed_amount | `BIGINT NULL` | 원금+확정 수수료; 미산정 NULL |
| annual_fee_rate | `DECIMAL(9,6) NULL` | 적용 연 수수료율 스냅샷; 0~1 |
| fee_calculation_snapshot | `JSON NULL` | 카드사 수수료 계산 근거·기간·반올림 |
| status | `VARCHAR(24) NOT NULL` | 회차 청구·출금·제외 상태 코드 |
| unpaid_principal_waived_at | `TIMESTAMP(6) NULL` | UTC 취소로 미납 원금 제외한 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (bill_item_id);
UNIQUE KEY uk_bill_item_installment (payment_transaction_id, installment_no);
UNIQUE KEY uk_item_payment_owner (bill_item_id, payment_transaction_id, user_id);
KEY ix_item_payment_card_owner (payment_transaction_id, user_id, user_card_id);
KEY ix_item_bill_card_owner (card_bill_id, user_id, user_card_id);
KEY ix_item_pending_month (user_card_id, billing_month, status);
CONSTRAINT fk_item_payment_owner FOREIGN KEY (payment_transaction_id, user_id, user_card_id) REFERENCES payment_transaction (payment_transaction_id, user_id, user_card_id);
CONSTRAINT fk_item_bill_owner FOREIGN KEY (card_bill_id, user_id, user_card_id) REFERENCES card_bill (card_bill_id, user_id, user_card_id);
CONSTRAINT ck_item_installment CHECK (installment_no > 0);
CONSTRAINT ck_item_month CHECK (DAYOFMONTH(billing_month) = 1);
CONSTRAINT ck_item_amounts CHECK (principal_amount >= 0 AND (fee_amount IS NULL OR fee_amount >= 0) AND ((fee_amount IS NULL AND billed_amount IS NULL) OR (fee_amount IS NOT NULL AND billed_amount IS NOT NULL AND billed_amount = principal_amount + fee_amount)));
CONSTRAINT ck_item_rate CHECK (annual_fee_rate IS NULL OR annual_fee_rate BETWEEN 0 AND 1);
CONSTRAINT ck_bill_item_version CHECK (version >= 0)
```

### refund — 청구 회차별 환급

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| refund_id | `BIGINT NOT NULL AUTO_INCREMENT` | 환급 번호 |
| payment_transaction_id | `BIGINT NOT NULL` | 취소된 원거래 |
| bill_item_id | `BIGINT NOT NULL` | 환급 청구 회차; 일시불도 필수 |
| user_id | `BIGINT NOT NULL` | 환급 대상 사용자 |
| linked_account_id | `BIGINT NULL` | 환급 계좌; 처리 전 확정 필요 |
| request_key | `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL` | 서버 생성 환급 작업 UUID; 재시도 유지 |
| principal_amount | `BIGINT NOT NULL` | 환급 원금 |
| fee_amount | `BIGINT NOT NULL` | 카드사 기준 환급 수수료; 확정값만 기록 |
| refund_amount | `BIGINT NOT NULL` | 확정 총 환급액 |
| due_date | `DATE NULL` | 한국 업무 날짜 환급 기한 |
| status | `VARCHAR(24) NOT NULL` | 환급 처리 상태 코드 |
| completed_at | `TIMESTAMP(6) NULL` | UTC 환급 성공 확인 시각 |
| fin_request_no | `CHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL` | 은행 환급 입금 요청의 기관거래고유번호. YYYYMMDD + HHMMSS + 일련번호 6자리; payment_transaction 절의 공통 채번 규칙 적용 |
| fin_transaction_no | `VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL` | 확인한 은행 입금 거래 참조 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (refund_id);
UNIQUE KEY uk_refund_item (bill_item_id);
UNIQUE KEY uk_refund_request (request_key);
KEY ix_refund_item_payment_owner (bill_item_id, payment_transaction_id, user_id);
KEY ix_refund_account_owner (linked_account_id, user_id);
KEY ix_refund_due (status, due_date);
CONSTRAINT fk_refund_item_owner FOREIGN KEY (bill_item_id, payment_transaction_id, user_id) REFERENCES bill_item (bill_item_id, payment_transaction_id, user_id);
CONSTRAINT fk_refund_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id);
CONSTRAINT ck_refund_amounts CHECK (principal_amount >= 0 AND fee_amount >= 0 AND refund_amount > 0 AND refund_amount = principal_amount + fee_amount);
CONSTRAINT ck_refund_completed CHECK (completed_at IS NULL OR linked_account_id IS NOT NULL);
CONSTRAINT ck_refund_version CHECK (version >= 0)
```

### cashflow_item — 반복 규칙·일회성 예정 항목의 기본값

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| cashflow_item_id | `BIGINT NOT NULL AUTO_INCREMENT` | 예정 내역 번호 |
| user_id | `BIGINT NOT NULL` | 내역 소유 사용자 |
| linked_account_id | `BIGINT NULL` | 예정 계좌; 미지정 가능 |
| title | `VARCHAR(150) NOT NULL` | 일정 표시 이름; 구매할 상품명은 product_name |
| direction | `VARCHAR(16) NOT NULL` | INCOME / EXPENSE |
| amount | `BIGINT NULL` | 발생 건 생성 시 기본 예정 금액; NULL은 금액 미정이며 0원이 아님 |
| scheduled_date | `DATE NOT NULL` | 한국 기준 첫 발생일; 등록 시각인 created_at과 구분 |
| recurrence_type | `VARCHAR(16) NOT NULL` | NONE / DAILY / WEEKLY / MONTHLY / YEARLY |
| recurrence_end_date | `DATE NULL` | 한국 기준 반복 종료일 |
| recurrence_day_of_month | `SMALLINT NULL` | 매월 반복 기준일(1~31), 그달에 없으면 말일(31은 매달 말일); MONTHLY만, NULL이면 첫 발생일의 날짜 |
| status | `VARCHAR(24) NOT NULL` | 예정 내역 상태 코드 |
| memo | `VARCHAR(500) NULL` | 사용자 메모; 민감정보 입력 주의 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |
| user_card_id | `BIGINT NULL` | 예정 결제 카드; 같은 사용자 소유 |
| merchant_category_code | `VARCHAR(64) NULL` | 예정 결제의 가맹점 업종 코드; API merchantCategoryCode, POL-004 「가맹점 업종 정책」 |
| product_category_code | `VARCHAR(64) NULL` | 구매할 물품 카테고리 코드; API productCategoryCode, 미지정 NULL |
| product_name | `VARCHAR(150) NULL` | 구매할 상품명; API productName, 일정 표시 이름 title과 별도 |
| item_kind | `VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'` | SCHEDULED / PURCHASE_PLAN; 기존 행은 일반 예정 항목 |
| payment_plan | `VARCHAR(16) NULL` | LUMP_SUM / INSTALLMENT; 카드 결제 방식 |
| installment_months | `SMALLINT NULL` | 일시불 1, 할부 총 개월 수; 비카드 항목 NULL |
| purchase_state | `VARCHAR(16) NULL` | 구매 계획만 ACTIVE / FULFILLED / CANCELED |
| previous_cashflow_item_id | `BIGINT NULL` | 이후 일정 변경 시 이전 규칙; 기존 규칙과 이력은 보존 |

```sql
PRIMARY KEY (cashflow_item_id);
KEY ix_cashflow_owner_date (user_id, scheduled_date);
KEY ix_cashflow_account_owner (linked_account_id, user_id);
CONSTRAINT fk_cashflow_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT fk_cashflow_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id);
CONSTRAINT ck_cashflow_direction CHECK (direction IN ('INCOME','EXPENSE'));
CONSTRAINT ck_cashflow_amount CHECK (amount IS NULL OR amount > 0);
CONSTRAINT ck_cashflow_recurrence CHECK (recurrence_type IN ('NONE','DAILY','WEEKLY','MONTHLY','YEARLY'));
CONSTRAINT ck_cashflow_end CHECK (recurrence_end_date IS NULL OR recurrence_end_date >= scheduled_date);
CONSTRAINT ck_cashflow_item_version CHECK (version >= 0);
UNIQUE KEY uk_cashflow_owner (cashflow_item_id, user_id);
KEY ix_cashflow_card_owner (user_card_id, user_id);
KEY ix_cashflow_previous_owner (previous_cashflow_item_id, user_id);
CONSTRAINT fk_cashflow_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id);
CONSTRAINT fk_cashflow_previous_owner FOREIGN KEY (previous_cashflow_item_id, user_id) REFERENCES cashflow_item (cashflow_item_id, user_id);
CONSTRAINT ck_cashflow_kind CHECK (item_kind IN ('SCHEDULED','PURCHASE_PLAN'));
CONSTRAINT ck_cashflow_card_direction CHECK (user_card_id IS NULL OR direction = 'EXPENSE');
CONSTRAINT ck_cashflow_plan CHECK (
        (payment_plan IS NULL AND installment_months IS NULL)
        OR (user_card_id IS NOT NULL AND payment_plan IS NOT NULL AND installment_months IS NOT NULL
            AND ((payment_plan = 'LUMP_SUM' AND installment_months = 1)
                OR (payment_plan = 'INSTALLMENT' AND installment_months >= 2))));
CONSTRAINT ck_cashflow_purchase CHECK (
        (item_kind = 'SCHEDULED' AND purchase_state IS NULL)
        OR (item_kind = 'PURCHASE_PLAN' AND direction = 'EXPENSE' AND user_card_id IS NOT NULL
            AND amount IS NOT NULL AND payment_plan IS NOT NULL
            AND purchase_state IS NOT NULL AND purchase_state IN ('ACTIVE','FULFILLED','CANCELED')));
CONSTRAINT ck_cashflow_recurrence_day CHECK (
        recurrence_day_of_month IS NULL
        OR (recurrence_type = 'MONTHLY'
            AND recurrence_day_of_month BETWEEN 1 AND 31
            AND DAY(scheduled_date) = LEAST(recurrence_day_of_month, DAY(LAST_DAY(scheduled_date)))));
```

금액·카드 등은 발생 건 생성 시의 기본값이다. 매월 반복의 n번째 회차는 첫 발생일에서 n달 뒤 그달의 기준일(recurrence_day_of_month, 없으면 말일)이다. 이후 일정 변경으로 나뉜 새 규칙은 첫 발생일이 말일로 당겨진 날(예: 31일 반복의 11/30)이어도 기준일을 이어받는다. 예보는 회차별 cashflow_occurrence를 사용하며 이 테이블의 금액을 추가 차감하지 않는다. 이후 일정 변경은 기존 규칙의 종료일을 변경 시작일 전날로 닫고 새 규칙을 previous_cashflow_item_id로 연결한다. NULL 금액은 미정이며 입력을 안내한다.

### cashflow_occurrence — 회차별 예정 수입·지출

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| cashflow_occurrence_id | `BIGINT NOT NULL AUTO_INCREMENT` | 예정 발생 건 번호; 실제 출금 기록 아님 |
| cashflow_item_id | `BIGINT NOT NULL` | 반복 규칙 또는 일회성 항목 |
| user_id | `BIGINT NOT NULL` | 규칙과 동일 사용자 |
| occurrence_date | `DATE NOT NULL` | 한국 기준 원래 발생일; 이번 건 날짜를 바꿔도 식별용으로 보존 |
| scheduled_date | `DATE NOT NULL` | 한국 기준 이번 건에 적용할 예정일 |
| title | `VARCHAR(150) NOT NULL` | 이번 건 일정 표시 이름; 구매할 상품명은 product_name |
| direction | `VARCHAR(16) NOT NULL` | INCOME / EXPENSE |
| amount | `BIGINT NULL` | 이번 건 예정 원화 금액; NULL이면 금액 입력 안내 후 예보 계산 제외 |
| linked_account_id | `BIGINT NULL` | 이번 건 계좌 직접 입출금 계좌; 같은 사용자 소유 |
| user_card_id | `BIGINT NULL` | 이번 건 예정 결제 카드; 같은 사용자 소유 |
| merchant_category_code | `VARCHAR(64) NULL` | 이번 건 가맹점 업종 코드; API merchantCategoryCode |
| product_category_code | `VARCHAR(64) NULL` | 이번 건 물품 카테고리 코드; API productCategoryCode, 미지정 NULL |
| product_name | `VARCHAR(150) NULL` | 이번 건 구매할 상품명; API productName, 일정 표시 이름 title과 별도 |
| payment_plan | `VARCHAR(16) NULL` | LUMP_SUM / INSTALLMENT; 비카드 항목 NULL |
| installment_months | `SMALLINT NULL` | 일시불 1, 할부 총 개월 수 |
| status | `VARCHAR(24) NOT NULL` | CONFIRMED 예정 / EXCLUDED 이번 건 제외; 기존 상태는 변환 없이 보존 |
| is_overridden | `BOOLEAN NOT NULL DEFAULT FALSE` | 이번 건만 수정했는지 여부; 이후 일괄 변경 시 판정 근거 |
| memo | `VARCHAR(500) NULL` | 이번 건 메모; 민감정보 입력 주의 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |
| version | `BIGINT NOT NULL DEFAULT 0` | JPA 낙관적 락 버전 |

```sql
PRIMARY KEY (cashflow_occurrence_id);
UNIQUE KEY uk_occurrence_slot (cashflow_item_id, occurrence_date);
UNIQUE KEY uk_occurrence_owner (cashflow_occurrence_id, user_id);
KEY ix_occurrence_owner_date (user_id, scheduled_date);
KEY ix_occurrence_rule_owner (cashflow_item_id, user_id);
KEY ix_occurrence_account_owner (linked_account_id, user_id);
KEY ix_occurrence_card_owner (user_card_id, user_id);
CONSTRAINT fk_occurrence_rule_owner FOREIGN KEY (cashflow_item_id, user_id) REFERENCES cashflow_item (cashflow_item_id, user_id);
CONSTRAINT fk_occurrence_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id);
CONSTRAINT fk_occurrence_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id);
CONSTRAINT ck_occurrence_direction CHECK (direction IN ('INCOME','EXPENSE'));
CONSTRAINT ck_occurrence_amount CHECK (amount IS NULL OR amount > 0);
CONSTRAINT ck_occurrence_card_direction CHECK (user_card_id IS NULL OR direction = 'EXPENSE');
CONSTRAINT ck_occurrence_plan CHECK (
(payment_plan IS NULL AND installment_months IS NULL)
OR (user_card_id IS NOT NULL AND payment_plan IS NOT NULL AND installment_months IS NOT NULL
AND ((payment_plan = 'LUMP_SUM' AND installment_months = 1)
OR (payment_plan = 'INSTALLMENT' AND installment_months >= 2))));
CONSTRAINT ck_occurrence_override CHECK (is_overridden IN (0,1));
CONSTRAINT ck_occurrence_version CHECK (version >= 0);
```

occurrence_date는 원래 회차를 식별하는 날짜이고 scheduled_date는 수정 후 예정일이다. 이번 건 날짜를 옮겨도 원래 회차를 다시 생성하지 않는다. amount=NULL이면 일정은 표시하고 입력을 안내하되 해당 금액은 예보 계산에서 제외한다. 미정 금액이 제외된 예보임을 알린다. 이 행은 실제 결제·출금 원장이 아니다.

### cashflow_payment_match — 예정 발생 건·실제 결제 연결

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| cashflow_payment_match_id | `BIGINT NOT NULL AUTO_INCREMENT` | 예정 발생 건과 실제 결제 연결 번호 |
| user_id | `BIGINT NOT NULL` | 예정 발생 건·결제와 동일 사용자 |
| cashflow_occurrence_id | `BIGINT NOT NULL` | 사용자가 동일 지출로 확인한 예정 발생 건 |
| payment_transaction_id | `BIGINT NOT NULL` | 실제 YAMU 결제; 가상 구매 계획으로 생성하지 않음 |
| matched_at | `TIMESTAMP(6) NOT NULL` | UTC 사용자 연결 확인 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

```sql
PRIMARY KEY (cashflow_payment_match_id);
UNIQUE KEY uk_match_occurrence (cashflow_occurrence_id);
UNIQUE KEY uk_match_payment (payment_transaction_id);
KEY ix_match_occurrence_owner (cashflow_occurrence_id, user_id);
KEY ix_match_payment_owner (payment_transaction_id, user_id);
CONSTRAINT fk_match_occurrence_owner FOREIGN KEY (cashflow_occurrence_id, user_id) REFERENCES cashflow_occurrence (cashflow_occurrence_id, user_id);
CONSTRAINT fk_match_payment_owner FOREIGN KEY (payment_transaction_id, user_id) REFERENCES payment_transaction (payment_transaction_id, user_id);
```

규칙+발생일 문자열 대신 cashflow_occurrence_id를 참조한다. 발생 건의 UNIQUE(규칙, 원래 발생일)와 연결 테이블의 발생 건·결제 UNIQUE를 함께 사용한다. 동일 지출인지 사용자가 확인한 뒤 연결하며 금액·날짜 유사성만으로 자동 확정하지 않는다. 금융망 외부 거래 중복까지 해결하는 구조는 아니다.

### notification — 사용자 알림

| 컬럼 | SQL 정의 | 설명 |
|---|---|---|
| notification_id | `BIGINT NOT NULL AUTO_INCREMENT` | 알림 번호 |
| user_id | `BIGINT NOT NULL` | 알림 수신 사용자 |
| notification_type | `VARCHAR(40) NOT NULL` | 알림 기능에서 정의한 유형 |
| deduplication_key | `VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL` | 동일 원인 중복 방지 키; 서버 생성 |
| title | `VARCHAR(150) NOT NULL` | 알림 제목 |
| body | `VARCHAR(1000) NOT NULL` | 알림 내용 |
| target_type | `VARCHAR(32) NULL` | 앱 이동 대상 유형; 권한은 서비스에서 확인 |
| target_id | `BIGINT NULL` | 앱 이동 대상 번호; 다형 참조이므로 FK 없음 |
| read_at | `TIMESTAMP(6) NULL` | UTC 읽은 시각 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |
| updated_at | `TIMESTAMP(6) NOT NULL` | UTC 수정 시각 |

```sql
PRIMARY KEY (notification_id);
UNIQUE KEY uk_notification_dedup (user_id, deduplication_key);
KEY ix_notification_owner_time (user_id, created_at);
CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user (user_id);
CONSTRAINT ck_notification_target CHECK ((target_type IS NULL AND target_id IS NULL) OR (target_type IS NOT NULL AND target_id IS NOT NULL))
```

## 관련 문서

- [스키마 안내·정책 원본 위치](README.md)
- [서비스 구현 책임·검증 방법](implementation.md)
- [Flyway SQL 전체 적용 순서](../../../be/src/main/resources/db/migration)

## 2026-11-03 구매 항목·혜택 조합 확장

아래 정의는 V20261103_1에서 추가한다. 생성 SQL과 JPA 매핑이 실행 기준이다.

### purchase_item — 혜택 판정 구매 항목

| 컬럼 | SQL 정의 | 의미 |
|---|---|---|
| purchase_item_code | `VARCHAR(64) NOT NULL PRIMARY KEY` | 대문자·숫자·밑줄 코드, CHECK |
| display_name | `VARCHAR(150) NOT NULL` | 표시 이름 |
| is_active | `BOOLEAN NOT NULL DEFAULT TRUE` | 0/1 CHECK, 비활성은 신규 선택 제한 |

사용된 코드 삭제·의미 변경·재사용 금지. 비활성화가 과거 거래의 코드를 바꾸지는 않는다.

### benefit_target_group — 대상 AND 경로

| 컬럼 | SQL 정의 | 의미 |
|---|---|---|
| benefit_target_group_id | `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` | 경로 ID |
| card_benefit_id | `BIGINT NOT NULL` | 특정 혜택 정책 버전 FK, 삭제 제한 |
| group_code | `VARCHAR(64) NOT NULL` | 고정 Seed 논리 코드 |
| is_excluded | `BOOLEAN NOT NULL` | 0/1 CHECK, 제외 경로 여부 |
| created_at | `TIMESTAMP(6) NOT NULL` | UTC 생성 시각 |

UNIQUE(card_benefit_id,group_code), UNIQUE(benefit_target_group_id,card_benefit_id). 별도 정책 버전은 없다.

### benefit_target_group_member — 경로 멤버

| 컬럼 | SQL 정의 | 의미 |
|---|---|---|
| benefit_target_group_id | `BIGINT NOT NULL` | 경로 |
| benefit_target_id | `BIGINT NOT NULL` | 대상 |
| card_benefit_id | `BIGINT NOT NULL` | 같은 혜택 연결 검증 |

PK(benefit_target_group_id,benefit_target_id). 그룹/혜택 및 대상/혜택 각각 복합 FK, 연쇄 삭제 없음. 빈 경로·CONDITION 멤버·그룹에 연결되지 않은 일반 대상은 서비스가 거절한다.

### benefit_application_group — 혜택 적용 방식

| 컬럼 | SQL 정의 | 의미 |
|---|---|---|
| benefit_application_group_id | `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` | 적용 그룹 ID |
| card_product_id | `BIGINT NOT NULL` | 상품 FK, 삭제 제한 |
| group_code | `VARCHAR(64) NOT NULL` | 논리 코드 |
| policy_version | `SMALLINT NOT NULL` | 양수 CHECK |
| application_mode | `VARCHAR(16) NOT NULL` | STACK/MAX_ONE/PRIORITY_ONE CHECK |

UNIQUE(card_product_id,group_code,policy_version), UNIQUE(benefit_application_group_id,card_product_id).

### benefit_application_member — 적용 그룹 혜택

| 컬럼 | SQL 정의 | 의미 |
|---|---|---|
| benefit_application_group_id | `BIGINT NOT NULL` | 적용 그룹 |
| card_benefit_id | `BIGINT NOT NULL` | 특정 혜택 버전, UNIQUE |
| card_product_id | `BIGINT NOT NULL` | 같은 상품 복합 FK 검증 |
| priority_order | `SMALLINT NULL` | NULL 또는 양수 CHECK |

PK(benefit_application_group_id,card_benefit_id). 그룹/상품, 혜택/상품 복합 FK, 삭제 제한. PRIORITY_ONE 필수 우선순위 및 STACK/MAX_ONE의 NULL 우선순위는 서비스 검증이다. 같은 우선순위의 동시 적용은 ID 정렬로 해결하지 않는다.

### 기존 테이블 추가 컬럼

| 테이블·컬럼 | SQL 정의 | 의미 |
|---|---|---|
| card_benefit.fallback_benefit_id | `BIGINT NULL` | 같은 상품의 기본 혜택 복합 FK |
| card_benefit.fallback_mode | `VARCHAR(32) NULL` | 현재 EXCESS_PAYMENT_AMOUNT |
| payment_transaction.purchase_item_code | `VARCHAR(64) NULL` | purchase_item FK, 삭제 제한, 과거 거래 NULL 보존 |
| payment_transaction.recommendation_result_json | `JSON NULL` | 승인 결과와 구분한 당시 추천 결과, 과거 NULL 보존 |

fallback 두 필드는 동시에 NULL이거나 동시에 값이어야 한다. MySQL AUTO_INCREMENT CHECK 제한 때문에 자기 참조는 INSERT/UPDATE 트리거로 차단한다. 한 단계·정률·양수 우대율·연쇄/순환 금지와 금액 한도 하나 검증은 조립기/계산기에서 수행한다.

추천 JSON은 schemaVersion=1, 후보별 netBenefit 필수, benefits[].amount nullable, unallocatedBenefitGroups 배열 필수다. NULL은 미확정이며 0원과 다르다. 단순 조회로 재계산·실적/사용량 갱신·과거 결과 생성을 하지 않는다. 결제 담당의 트랜잭션·재시도·소유권/동시 저장 책임은 [연결 계약](../benefit-composition.md)을 따른다.
