# 스키마 구현 지침

DB 스키마·업무 엔티티·서비스를 구현하는 개발자와 AI를 위한 문서다. [저장소 규칙](../../../AGENTS.md)과 [BE 규칙](../../../be/AGENTS.md), [JPA·Flyway 규칙](../jpa-flyway.md)을 함께 따른다.

## 읽는 순서와 변경 범위

1. [스키마 안내](README.md)에서 담당 기능의 테이블을 찾는다.
2. [컬럼 사전](dictionary.md)에서 그 테이블의 저장 정의를 확인한다. 이 문서에 컬럼 표 사본을 만들지 않는다.
3. 아래 서비스 책임과 미확인 사항을 읽고, 스키마 안내에 연결된 현재 Notion 정책·API 계약을 확인한다. archive의 과거 제안으로 최신 결정을 덮어쓰지 않는다.
4. DB 변경 시 Flyway SQL·컬럼 사전·관련 제약 테스트를 함께 맞춘다. 역할·흐름이 바뀌면 README, 서비스 책임이 바뀌면 이 문서를 갱신한다. Merge된 마이그레이션은 수정하지 않고 새 파일을 추가한다.

이 폴더는 초기 물리 설계 초안과 구현 계약이다. 업무 엔티티·서비스 전체 구현, 운영 DB 적용, 외부 약관 수집이 완료됐다는 뜻은 아니다. 새 기능 정책은 임의로 정하지 않고 Notion의 해당 요구사항·정책에 기록한다.

## 적용 범위와 미확인 사항

- 최신 인증 구조는 email/password_hash 없이 PIN·생체인증, 등록 기기 필수 인증 이력, 휴대폰 해시+생년월일 UNIQUE다.
- DEC-001의 로그인은 기기 서명과 PIN/생체 확인을 함께 요구한다. PIN 전용 기기도 공개키·알고리즘을 필수 등록하며 device_uuid만으로 기기 소유를 인증하지 않는다. biometric_enabled는 생체인증 사용 여부이며 서명 키 등록 여부가 아니다. 공개키의 실제 형식·알고리즘·서명 유효성은 서비스가 검증한다. 생체 사용 전환 시 Android 클라이언트의 키 보호 설정·키 교체 흐름을 맞추고 변경 이력은 user_device_event에 남긴다.
- V20261027_3는 모든 기기의 public_key·key_algorithm을 NOT NULL로 변경한다. 기존 NULL 기기가 있으면 사전 CHECK에서 적용을 중단하며 임의 키 입력·기기 삭제로 우회하지 않는다. 해당 기기의 키 등록을 완료한 후 실패한 Flyway 이력과 실제 제약 상태를 담당자가 확인해 복구한다. clean/repair는 자동 실행하지 않는다. 빈 문자열·잘못된 키의 거절과 기기 서명 확인은 NOT NULL만으로 보장되지 않는다.
- 금융망 사용자 ID는 API 명세 V2의 2026-10-26 변경에 맞춰 `y{랜덤 9자리}@yamu.app`(19자)으로 서버에서 생성한다. SSAFY userId의 40자 제한을 지키며 ssafy_member_id에 저장해 금융망 요청의 userId로 전달한다. 최초 생성한 ID는 재시도·가입 이어하기에도 재사용하되, 중복 오류 E4002가 확인되면 해당 ID의 다른 계정에 연결하지 않고 새 ID를 생성해 다시 요청한다. 실제 사용자 이메일 입력·메일 발송·메일 인증·이메일 로그인은 없다. YAMU의 숫자 user_id 및 별도로 암호화 저장하는 금융망 userKey와 구분한다. V20261027_2는 컬럼 COMMENT만 수정하며 기존 ID를 변환하거나 VARCHAR(255)·NULL 허용·UNIQUE를 변경하지 않는다. ID 생성·중복 처리 구현은 서비스 책임이다.
- ‘기존 알림·잔고 설정 3개’라는 불명확한 문구는 폐기했다. notification_settings·forecast_settings는 API에 맞춘 설정 저장 컬럼이다. 2026-10-26 확정: 앱 내 알림 설정은 실적·잔고 위험·거래 3개 묶음이며 모두 기본 켜짐이다. 별도 가입 선택 단계는 두지 않는다. 보안 알림은 항상 제공한다. 잔고예보의 안전잔액 등 다른 설정 기본값을 이 결정으로 확정하지 않는다. JSON 키·값·참조 소유권은 서비스에서 검증한다.
- 암호화 값은 알고리즘에 종속되지 않는 봉투를 위한 VARBINARY(2048)로 설계했다. **기술 설계 가정:** 키 식별자·nonce·tag·암호문이 들어가는 봉투 크기를 암호화 구현과 대조해야 한다. 길이를 넘으면 자르지 않고 거절한다. 키는 DB에 저장하지 않는다. 휴대폰·계좌·카드 조회 해시는 정규화한 값의 keyed HMAC-SHA-256(32바이트)이다. PIN은 빠른 SHA 해시가 아닌 솔트 포함 비밀번호 해시를 사용한다.
- 금융망 필드 전체의 길이 계약 검증은 미완료다. fin_request_no는 확인한 공통 Header 명세에 따라 CHAR(20)으로 확정했고 생성 규칙은 컬럼 사전에 기록했다. 그 밖의 외부 참조 문자열 길이와 암호화 봉투 2048바이트는 **임시 기술 상한**이므로 운영 연동 전 각 API 계약과 대조한다. 팀 카드 상품·가맹점 번호는 외부 코드와 무관한 서버 PK다.
- 정책 약관 수집은 미완료다. 반올림·반영 시점·포인트 환산율·수수료가 NULL이면 미확인 상태다. 계산 엔진은 NULL을 임의로 0 또는 절사로 바꾸지 않는다. 할부 수수료는 원금과 분리하고 청구 확정 전에 계산을 완료한다. 지원 개월 수·금리·세션 기간·개인정보 보관 기간을 SQL 기본값으로 정하지 않았다.
- API 문서 일부에는 과거 로그인 방식·제한 시간 등의 가정이 남을 수 있다. 구현 전 현재 V2의 해당 정책과 API 본문을 확인하고, 명시된 확정 사항과 과거 가정이 충돌하면 관련 문서만 확인해 맞춘다. 과거 대화나 archive만으로 새로운 규칙을 추정하지 않는다. 상세 API 페이지 전체를 전수 검증한 상태는 아니다.

## 공통 규칙

- 모든 PK/FK는 signed BIGINT. 원화는 BIGINT, 포인트는 DECIMAL(19,6), 비율은 DECIMAL(9,6)이다.
- created_at은 모든 테이블, updated_at은 수정 가능한 테이블에 있다. JPA Auditing이 UTC로 채우며 SQL에서 현재 시각으로 자동 갱신하지 않는다. TIMESTAMP의 MySQL 8.4.11 범위는 2038년까지다.
- 한국 기준 일자는 DATE, 월은 해당 월 1일이다. 실적 월을 UTC 승인 시각에서 그대로 추출하지 않는다.
- version은 동시 변경 대상에 두지만 JPA @Version 또는 WHERE version 조건을 사용해야 락 효과가 있다. 트랜잭션과 재시도는 서비스 구현 책임이다.
- 이름은 utf8mb4_0900_bin으로 비교한다. UUID·요청 키는 ascii_bin으로 대소문자를 구분하며 앱은 표준 소문자 UUID로 정규화한다.
- 별도 테이블의 행·합계·기간 겹침은 CHECK로 검사할 수 없다. 아래에 DB와 서비스의 책임을 구분했다.
- 카드 별칭은 user_card.card_alias, 계좌 별칭은 linked_account.account_alias에 저장한다. 둘 다 VARCHAR(100) NULL로 미지정을 허용한다. 별칭은 상품명·금융망 계좌명과 별도로 관리하며 식별 키로 사용하지 않는다.
- 각 컬럼 설명·타입·필수 여부·기본값과 모든 키 정의는 [컬럼 사전](dictionary.md)과 연결된 Flyway SQL을 따른다. NULL은 미확인·미배정인 경우와 실제 0을 구분한다.
- notification_settings는 performanceEnabled·balanceRiskEnabled·transactionEnabled 세 boolean을 저장한다. 실적 부족, 잔고 위험, 거래(결제 결과·청구·출금·미납·환급)의 새 앱 내 알림 생성 여부를 제어한다. 반복 지출 후보·혜택 변경·월간 리포트 알림은 제외한다. 카드별·D-day·시각은 1차 사용자 설정에 넣지 않는다. 푸시 지원 여부는 별도 미정이다.
- forecast_settings는 horizon·alertThreshold·defaultDays·baseAccountId·incomeCashflowItemId·dailyLivingCostTarget 등의 잔고예보 설정을 담는다. 같은 안전잔액은 forecast_settings.alertThreshold 한 곳에서 관리한다. baseAccountId·incomeCashflowItemId가 사용자 소유 데이터인지 서비스에서 검사하며, 이 JSON 참조에 DB FK가 있는 것처럼 취급하지 않는다.

### 알림 설정의 초기값과 변경

가입 시 서버가 다음 값을 저장한다. 사용자는 이후 설정 화면에서 각 묶음을 끌 수 있다.

```json
{
  "performanceEnabled": true,
  "balanceRiskEnabled": true,
  "transactionEnabled": true
}
```

- SQL 컬럼 정의는 JSON NULL을 유지한다. 위 값은 DB DEFAULT가 아니라 가입 서비스가 넣어야 하는 초기값이다. 아직 가입 서비스는 구현되지 않았다.
- NULL 또는 누락된 설정 항목은 서버에서 기본 켜짐(true)으로 해석한다. 명시적으로 저장한 false는 유지하며 true로 덮어쓰지 않는다.
- PATCH /api/notifications/settings에는 변경할 boolean 항목만 보낸다. 생략한 값은 기존 값을 유지한다. 알림을 꺼도 이미 생성된 알림은 삭제하지 않는다.
- 보안 알림은 이 JSON에 저장하지 않고 항상 제공한다. 조회 API의 securityEnabled는 읽기 전용 true다.

## 관계·인덱스와 서비스 책임

- 명칭 변경: payment_limit_usage의 이전 이름은 payment_limit_impact다. V20261027_1 마이그레이션에서 테이블과 PK 이름을 바꾸며 기존 행·인덱스·FK·한도 사용량·복구 시각을 보존한다. 이미 Merge된 초기 SQL과 archive의 과거 이름은 수정하지 않는다.

- 2026-10-27 공식 적용 기간: CARD_BENEFIT·LIMIT_GROUP의 valid_from은 공식 시작일을 모르면 NULL로 보관한다. created_at(등록 시각), 카드 출시일, 임의 과거 날짜를 실제 시작일 대신 넣지 않는다. valid_until은 공식 종료일이며 종료일 당일부터 사용하지 않는다. NULL은 종료일을 확인하지 못했거나 아직 정해지지 않았다는 뜻이지 현재 유효함을 보증하지 않는다. 두 날짜를 모두 아는 경우에만 종료일이 시작일보다 뒤인지 CHECK로 강제한다.
- 현재 결제·추천에는 공식 자료로 현재 제공 여부를 확인한 현행 규칙 버전을 사용한다. 알려진 시작일이 미래이거나 종료일이 지났으면 제외한다. valid_from이 NULL인 여러 버전을 모두 적용하거나 가장 큰 버전 번호만으로 현행이라고 추정하지 않는다. 날짜 미확인 시 현행 버전을 선택하는 운영 절차는 별도 관리하고, 기존 POL-009의 다음 달 YAMU 정책 반영 일정과 공식 약관 날짜를 혼동하지 않는다. 과거 거래·취소는 당시 저장한 혜택 버전·계산 스냅샷을 사용하며, 시작일 미확인 규칙을 과거에 소급 적용하지 않는다.
- 분류 명칭은 2026-10-27 사용자 결정과 [POL-004](https://app.notion.com/p/f5731720f3588254860601b04ec14dd7)를 따른다. 「물품 카테고리 정책」은 구매 품목 선택과 productCategoryCode를, 「가맹점 업종 정책」은 영업 종류와 merchantCategoryCode를 관리한다. 물품의 계층형 분류 방향은 유지하며 단계 수·코드 목록은 미정이다. 가맹점 업종은 초기 카드 약관에 필요한 코드부터 정하고 필요 시 추가하되 기존 코드의 의미는 보존한다. 가맹점 업종의 단계 수·브랜드 업종 배정·혜택 상하위 매칭·이력 보존·DB/코드 파일 관리 방식도 후속 확정한다. 기존 F/F01/F01-CAFE·3단계·접두어 매칭은 초안이다.

- 2026-10-26 브랜드 단위 설계: MERCHANT는 스타벅스 같은 브랜드마다 한 행이며 brand_code는 필수·고유하다. 개별 지점명·주소·좌표와 브랜드의 고정 channel은 저장하지 않는다. 지도 API가 찾은 매장은 외부 매장 ID·이름·주소와 내부 브랜드의 연결 정보로 다루며, 지도 공급자·매칭 방식·캐시 보관은 지도 기능 구현 시 확정한다. 이름이 비슷하다는 이유만으로 브랜드 매칭을 확정하지 않는다.
- 혜택 대상은 BRAND / CATEGORY / ALL과 CONDITION으로 구분한다. BRAND는 target_value 문자열이 아니라 merchant_id FK로 MERCHANT를 참조한다. 이전 MERCHANT 대상 코드는 사용하지 않는다. 브랜드·업종·전체의 포함 대상 중 하나가 맞고, 추가 포함 CONDITION은 모두 확인·충족해야 한다. 제외 대상·조건 하나라도 맞으면 제외한다. 예: BRAND=스타벅스 포함 + CONDITION=FACILITY:DEPARTMENT_STORE 제외 + CONDITION=CHANNEL:ONLINE 제외. 이 조합·판정은 서비스 책임이며 FK/CHECK만으로 혜택 적용을 계산하지 않는다.
- 거래 조건은 PAYMENT_TRANSACTION.payment_channel(ONLINE / OFFLINE / UNKNOWN), facility_type, merchant_context_snapshot에 최초 접수 당시 값을 저장한다. snapshot에는 당시 brandCode·merchantCategoryCode(과거 snapshot의 categoryCode)와 확인 근거가 된 POS 식별자·설정 버전 등을 기록한다. NULL 시설·UNKNOWN 채널은 미확인이고 일반 매장·오프라인을 뜻하지 않는다. 시설 코드는 [컬럼 사전](dictionary.md)의 payment_transaction 절에 있는 확정 목록을 사용하며, 조건 코드·판정 규칙의 기준은 [FR-014 혜택 적용 조건 검증](https://app.notion.com/p/e0431720f35882188d5101733ebd9269)에서 관리한다. 미지원 조건은 미확인으로 처리한다.
- 신뢰·재시도: 인증된 POS의 서버 관리 설정에서 브랜드·채널·입점 시설을 확인한다. 사용자 앱이나 지도 결과가 보낸 값을 곧바로 결제 사실로 확정하지 않는다. request_fingerprint는 POS 식별자·브랜드·금액 등 불변 요청을 비교하며, 같은 키 재시도에는 최초 저장한 거래 조건을 재사용한다. POS 설정이 나중에 바뀌어도 과거 거래의 조건을 다시 덮어쓰지 않는다. 승인·취소·혜택 재현도 당시 snapshot과 정책 버전을 사용한다.
- 조건 미확인: 혜택 판단에 필요한 채널·시설·업종이 없으면 혜택을 확정하지 않고 VALUE_UNVERIFIABLE 등 기존 데이터 부족 경로로 처리한다. 제외 조건이 미확인이라는 이유로 제외 대상이 아니라고 단정하지 않는다. 승인 전 조건과 snapshot의 일치·확인 완료 여부는 서비스에서 검사한다. 별도 POS·매장 테이블과 혜택 판정 서비스 구현을 이번 DDL에 추가한 것은 아니다.
- 소유권: 계좌 연결 인증은 로그 사용자와 계좌 소유자가 같아야 한다. 카드·청구·예정 내역·환급 계좌는 복합 FK로 동일 사용자를 강제한다. 인증 로그도 등록 기기 소유자를 강제한다.
- 카드 등록 전 데이터: 서버가 사용자 명의로 미리 준비한 USER_CARD는 PENDING이며 registered_at은 NULL이다. 사용자가 실제 등록하면 최초 등록 시각을 채운다. 이후 UNREGISTERED로 해제해도 최초 등록 시각과 거래 이력은 보존한다. SQL은 registered_at의 NULL을 허용하며 상태 전환·최초 등록 시각 보존은 서비스에서 검증한다.
- 상품: 혜택·한도 연결과 적용 결과·월 사용량은 같은 카드 상품이어야 한다. 청구 회차의 카드·사용자는 원거래 및 청구서와 일치한다. 환급은 원거래·사용자와 일치하는 BILL_ITEM을 반드시 참조한다.
- 논리 ERD 보완: 카드 선택 전 결제 요청을 저장할 수 있도록 PAYMENT_TRANSACTION의 카드·상품 FK는 함께 NULL을 허용한다. 승인 시각이 생기면 카드·승인액·실적·귀속 월·회차 수가 반드시 존재한다. REFUND의 BILL_ITEM은 최신 합의대로 필수다. 계좌 연결 로그의 UNIQUE 1:1은 일괄 계좌 선택 흐름과 충돌해 두지 않았다. 한 번 사용 인증은 서버·Redis에서 소비하고 본인인증 완료 시각은 USER에 기록한다.
- 재연결: 사용자+은행+계좌 해시 UNIQUE로 기존 LINKED_ACCOUNT를 재사용한다. 인증 로그는 성공·목적·만료 여부를 서버에서 검증한다. 현재 스키마에 계좌 잔액 원장은 없다.
- 멱등성: POS request_key UNIQUE와 불변 요청 fingerprint를 함께 사용한다. 중복 INSERT 충돌 시 기존 행과 내용을 비교하고 기존 처리 상태를 반환한다. UNIQUE만으로 재시도 응답·금융망 중복 출금이 해결되지는 않는다.
- 정책: 상품+논리 코드+정책 버전 UNIQUE. policy_version은 SMALLINT와 양수 CHECK로 1~32767을 사용한다. 번호는 전체 카드의 통합 누계가 아니라 상품 내 각 혜택 코드·한도 그룹 코드별로 관리한다. 매일 한 버전씩 등록해도 약 89.7년 분량이므로 현재 범위에 충분하다고 판단했다. 상한 도달 시 순환·초기화하지 않고 타입 확장 마이그레이션을 한다. 적용 기간은 시작 포함·끝 미포함, 실적 구간도 하한 포함·상한 미포함이다. 기간 중첩·동시 정책 수정·다음 달 적용은 서비스에서 검증한다. 과거 정책과 계산 스냅샷을 덮어쓰지 않는다.
- 카드 등록 초기화: 사전 준비한 seed로 전월·당월 실적과 당월 혜택 사용량을 확정한 뒤에만 등록 완료한다. 신규 카드·확인된 무거래는 0으로 명시 저장한다. 필수 seed 누락·검증 오류·집계 실패는 등록 실패로 처리하고 부분 반영을 롤백한다. 준비·확인 불가 상태 컬럼은 두지 않는다. USER_CARD.status의 PENDING은 사용자 등록 전 카드를 뜻하므로 그대로 유지한다. 기준: POL-008·FR-070.
- 한도: 일별 합계 테이블을 추가하지 않는다(FR-060). 결제 당시 카드의 거래·혜택·한도 사용 내역과 취소 이력으로 당일 사용량을 검사한다. 카드 또는 집계 행 잠금 아래 검사·승인·집계 수정·기록을 한 트랜잭션으로 수행한다. 취소 시 개별 사용량을 근거로 월간 한도 사용 합계와 월간 실적 합계를 모두 조정한다. 정책 변경 전 거래 취소는 당시 그룹 버전으로 복구한다.
- 한도 단위: S15P31E103-151의 V20261028_3은 기존 AMOUNT를 KRW로 이관하고 limit_group.limit_unit을 KRW / POINT / COUNT로 제한한다. 한도값·실적 구간별 한도·사용량·잔여량은 연결된 그룹 단위를 따른다. 숫자·정책 버전·거래·취소 기록은 바꾸지 않는다. 이관은 포인트 환산 작업이 아니며 원·포인트·횟수를 단위 확인 없이 합산하지 않는다. 한도·사용량의 BIGINT는 유지하므로 이번 변경은 소수 포인트 한도 지원을 추가하지 않는다. MySQL DDL은 전체 롤백이 보장되지 않으므로 서버 쓰기를 중지한 배포 절차에서 적용하고, 중간 실패 시 실제 CHECK와 Flyway 이력을 확인한다. clean/repair를 자동 실행하지 않는다.
- 할부: 거래+회차 UNIQUE. 원금 나머지는 마지막 회차. 한 회차가 원거래의 회차 수를 넘지 않는지와 전체 원금 합계는 서비스에서 검사한다. fee_amount=NULL을 무이자로 간주하지 않는다. bill_item의 원금+수수료=청구액은 CHECK로 검사한다.
- 취소: 출금 완료 원금은 환급, 미확정 원금은 제외, 확정 후 출금 대기분은 출금 성공 후 환급, 취소된 미납 원금은 납부 대상에서 제외하고 환급하지 않는다. 확정 청구 원본은 유지하며 canceled_unpaid_principal과 회차 제외 시각으로 조정 근거를 남긴다. 수수료 정산은 카드사 조건을 따른다. 환급 전 원거래 취소·실제 출금·환급액 상한·계좌 유효성을 서비스에서 검사한다.
- 인덱스: 소유자+발생 시각은 사용자 목록, 상태+예정일은 배치, 한도+사용일은 일별 검사, 각 UNIQUE는 중복 요청·회차·집계·알림 방지다. 복합 FK에는 필요한 접두 인덱스를 명시했다.
- 상태·목적 전체 목록이 합의되지 않은 필드는 VARCHAR로 두고 임의 CHECK를 만들지 않았다. 인증 수단·사용자/기기 상태 등 합의된 코드만 CHECK로 제한한다.
- 삭제는 RESTRICT. 탈퇴 시 상태만 바꾸는 것을 개인정보 파기로 간주하지 않는다. 보존·파기 정책 확정 후 식별정보 익명화·삭제를 서비스에서 구현한다.
- JPA 후속 엔티티는 기존 LocalDateTime 공통 필드를 TIMESTAMP(6)에 맞게 명시 매핑해야 한다. 기존 감사 테스트 테이블은 변경하지 않는다. 업무 엔티티 전체가 없으므로 현 테스트의 Hibernate validate는 업무 엔티티 전체 매핑 검증을 뜻하지 않는다.

## 분류 필드 명칭과 저장 매핑

| API 필드 | 문서 명칭·사용 위치 | 현재 DB 저장 |
|---|---|---|
| productCategoryCode | 물품 카테고리 코드. 소비 판단·가상 구매 시뮬레이션·구매 계획의 품목 선택 | cashflow_item.product_category_code, cashflow_occurrence.product_category_code |
| merchantCategoryCode | 가맹점 업종 코드. 가맹점 업종별 혜택·소비 집계, 예정 결제 업종 | merchant.merchant_category_code, cashflow_item.merchant_category_code, cashflow_occurrence.merchant_category_code. 혜택 대상 CATEGORY는 target_value |
| productName | 구매할 상품명. 예: 구매 예정 노트북 모델명 | cashflow_item.product_name, cashflow_occurrence.product_name. VARCHAR(150) NULL |
| title | 일정 표시 이름. 예: 노트북 구매 예정 | cashflow_item.title, cashflow_occurrence.title. 기존 이름·필수 여부 유지 |
| merchantId / brandCode | 가맹점 ID / 브랜드 코드 | merchant.merchant_id / merchant.brand_code 및 기존 거래 참조 |

API 명세서 V2의 목록은 「물품 카테고리 목록」과 「가맹점 업종 목록」으로 관리한다. 이슈 S15P31E103-145의 V20261027_5는 merchant·cashflow_item·cashflow_occurrence의 category_code를 merchant_category_code로 변경하고 기존 업종 값·인덱스를 보존한다. 두 현금흐름 테이블에 product_category_code·product_name을 추가하며 기존 행은 NULL로 둔다. 일정 이름 title이나 기존 업종으로 상품 정보를 추정해 채우지 않는다. 신규 발생 건 생성 시 규칙의 상품 정보를 복사하고 THIS_ONLY 수정은 해당 발생 건의 상품 정보만 변경한다. 물품만 지정한 시뮬레이션은 금액·출금 일정 기준 잔고 비교에 사용하되 필요한 가맹점 업종·브랜드 근거 없이 혜택을 확정하지 않는다. 부족 정보의 응답 표현은 계산 API 구현 시 확정한다. 코드 목록의 DB 테이블/코드 파일 관리 방식은 이번 컬럼 추가로 확정하지 않는다.
## Epic 7 현금흐름 보완 — 2026-10-27 승인

- 승인 원본: 사용자가 첨부한 「Epic 7 잔고예보 및 소비 시뮬레이션 DB 구조 보완 요청」의 2~5번 및 후속 반복 규칙·발생 건 분리 결정. 첨부 원문의 새 Notion 페이지 ID는 현재 연결에서 조회되지 않아 새 페이지를 수정했다고 간주하지 않는다. 로컬 저장 정의는 이 문서와 컬럼 사전에 모으며 별도 문서 사본을 만들지 않는다.
- `cashflow_item`은 반복 규칙·일회성 항목의 기본값, `cashflow_occurrence`는 날짜별 예정 건이다. 일회성도 발생 건 한 개를 생성한다. 규칙과 발생 건을 모두 차감하지 않는다. 새 등록·발생 건 생성은 서비스 트랜잭션에서 처리하며 DB가 미래 반복 건을 자동 생성하는 것은 아니다. S15P31E103-65 결정: 반복 회차는 조회 시 규칙에서 계산하며 조회는 발생 건을 생성하지 않는다. 발생 건 행은 등록 시 첫 회차와, 사용자가 그 회차만 수정(`is_overridden=true`)하거나 제외(`status=EXCLUDED`)한 회차에만 저장한다. 목록·예보는 계산한 회차에 저장된 행을 덮어 만든다. UNIQUE(규칙, 원래 발생일)로 같은 회차의 재시도·동시 저장 중복을 막는다.
- `occurrence_date`는 한국 날짜 기준 원래 회차 식별값이다. THIS_ONLY는 해당 행의 `scheduled_date`·금액·카드·계좌·업종·메모 등을 갱신하고 `is_overridden=true`로 표시한다. 이번 건 제외는 행을 지우지 않고 `status=EXCLUDED`로 보존한다. 실제 결제 연결이 있는 건은 단순 예정 지출 수정으로 실제 결제·청구를 변경하지 않는다.
- THIS_AND_FUTURE는 적용일 전날로 기존 규칙을 닫고 새 규칙을 `previous_cashflow_item_id`로 연결한다. 적용일이 규칙의 첫 회차 이전이면 규칙을 제자리에서 바꾼다. 과거 발생 건·사용자 확인 연결을 보존한다. 이전 규칙 연결의 자기 참조·순환은 서비스가 거절한다. S15P31E103-65 결정: 이후 수정과 전체 삭제는 `previous_cashflow_item_id`로 이어진 규칙 전체(시리즈)에 적용한다. 이후 수정은 적용일 이후의 뒤 규칙과 개별 수정한 미래 건을 지우고 새 규칙이 덮는다. 전체 삭제는 시리즈에서 오늘(한국 날짜) 이후를 없애며, 첫 발생일이 오늘 이후인 규칙은 행째 지우고 시작한 규칙은 어제로 닫는다. 변경 범위에 실제 결제 연결 건이 있으면 요청을 거절한다. 같은 시리즈의 변경은 첫 규칙 행 잠금으로 차례로 처리한다. 실제 결제 취소 후 계획 재활성화 여부는 아직 미정이며 자동 정책으로 추정하지 않는다.
- 사용자 확정: 금액 미정 지출은 `amount=NULL`로 저장하고 일정에는 표시한다. 금액 입력을 안내하며 해당 금액은 예보 차감에서 제외한다. 제외된 미정 지출이 존재한다는 사실을 응답·화면에 표시하고 예보를 완전한 확정 잔액처럼 안내하지 않는다. 실제 숫자 금액은 양수이고 0을 미정 대용으로 쓰지 않는다. 입력 후 해당 발생 건에 반영한다. 발생 건의 NULL은 그 건의 미정 값이며 규칙 기본 금액으로 무조건 대체하지 않는다.
- 카드·업종: 규칙과 발생 건의 카드·계좌 소유권은 복합 FK로 강제한다. 카드와 계좌 동시 지정의 허용·거절 규칙은 API 담당 협의 사항으로 유지한다. 이 DDL은 둘의 배타성을 임의 강제하지 않는다. 신용카드 지출은 카드 청구 일정·출금 계좌, 체크카드는 실제 계좌 출금의 중복 방지 규칙을 따른다. 체크카드 할부 거절·지원 개월 수는 서비스가 카드 상품과 대조한다.
- 구매 계획: `direction=EXPENSE`, `item_kind=PURCHASE_PLAN`이며 카드·금액·결제 방식·구매 상태를 저장한다. 일시불은 1개월, 할부는 2개월 이상이다. 시뮬레이션 결과 시계열은 영구 저장하지 않는다. 계획 저장만으로 PAYMENT_TRANSACTION·CARD_BILL·BILL_ITEM을 생성하지 않으며 예보에만 가상 청구를 계산한다. 구매 상태 변경과 발생 건·연결의 일관성은 서비스 트랜잭션 책임이다.
- 실제 결제 연결: 원문 제안의 (cashflow_item_id, occurrence_date) 대신 분리된 `cashflow_occurrence_id`를 참조한다. 발생 건의 회차 UNIQUE와 match의 발생 건·결제 각각 UNIQUE로 일대일 연결을 보장하며 복합 FK로 소유권도 강제한다. 사용자 확인 전 날짜·금액 유사성만으로 자동 제외하지 않는다. 확인된 연결의 예정 건만 가상 차감에서 제외하고 실제 승인·청구 데이터를 사용한다. 연결 가능한 실제 결제 상태, plan 완료 상태 전환, 금융망 외부 거래 중복 범위는 서비스에서 확인한다. 신용카드 승인 총액과 회차별 청구액을 동시에 차감하지 않는다.
- 소비 분류: `spending_class`는 LIVING/LARGE/EXCLUDED, `spending_class_source`는 AUTO/USER다. 기존 결제는 두 값 모두 NULL(미분류)로 보존하고 임의 자동 분류하지 않는다. USER 수정은 재분류 시 덮어쓰지 않으며 메모는 100자, 빈 문자열 삭제 요청은 NULL로 정규화한다. EXCLUDED는 읽기 전용이며 사용자 요청으로 변경하지 못하게 한다. 소비 분류 수정은 승인액·카드·실적 인정액을 바꾸지 않는다. 자동 분류 임계값은 기존 API의 가정이지 이번 승인으로 새로 확정한 계산 정책이 아니다.
- 기존 데이터 이관: V20261027_4는 기존 규칙·금액·상태·감사 시각을 보존하고 기록된 첫 발생일 한 건만 복사한다. 이후 무한 반복 건이나 실제 결제 연결·소비 분류를 만들어내지 않는다. 기존 ACTIVE 등 상태는 원문 그대로 보존하며 서비스가 과거 상태와 현재 CONFIRMED/EXCLUDED의 해석을 대조한다. source=DETECTED·탐지 근거·무시 이력은 6-2 미확정으로 추가하지 않았다.

## 검증 방법과 확인된 범위

2026-10-28 최신 develop(482be8f) 병합 후 한도 단위 SQL을 V20261028_3으로 변경했다. 격리 MySQL 8.4.11에서 스키마 테스트 29개가 통과했으며 실패·오류·생략은 0개다. 팀원의 V20261028_1·V20261028_2 다음에 한도 단위 SQL 적용, 기존 한도·사용·복구 기록 보존, 허용값·잘못된 값 거절과 재실행을 확인했다. 아래의 이전 로컬 실행 기록에 나오는 V20261028_1은 번호 변경 전 한도 단위 SQL을 뜻한다. 이후 사용자 승인으로 기존 로컬 DB를 Git 제외 SQL 파일에 백업한 뒤 MySQL 전용 볼륨만 초기화했다. 새 MySQL 8.4.11에서 bootRun으로 전체 9개 마이그레이션 적용과 HTTP 200·DB/Redis UP을 확인했다. Redis·기존 암호화 키는 유지했으며 repair는 실행하지 않았다.

2026-10-28 S15P31E103-151: 격리 MySQL 8.4.11에서 스키마 테스트 29개가 통과했으며 실패·오류·생략은 0개다. LimitUnitMigrationTest는 기존 AMOUNT→KRW 이관, COUNT 및 한도·실적 구간·월간 사용량·취소 기록·기존 마이그레이션 체크섬 보존, KRW/POINT/COUNT 허용, AMOUNT·잘못된 값·NULL 거절과 재실행 시 추가 적용 없음을 확인한다. 추가로 local 프로필 bootRun에서 로컬 MySQL 8.0.46에 V20261028_1이 정상 적용됐고 KRW 17개·COUNT 24개를 확인했다. /api/health는 HTTP 200·status=ok, /actuator/health는 DB·Redis 모두 UP이었으며 확인용 서버는 종료했다. 개인 application-local.yml이 없어 해당 실행 세션에만 DB 설정과 기존 로컬 키를 전달했다.

최근 확인: 2026-10-27 격리 MySQL 8.4.11에서 스키마 테스트 28개(InitialSchemaMigrationTest 24개·PaymentLimitUsageMigrationTest 1개·DeviceSigningKeyMigrationTest 1개·CashflowMigrationTest 1개·CategoryProductMigrationTest 1개)를 포함한 전체 50개 테스트가 통과했으며 실패·오류·생략은 0개다. 이름 변경 시 기존 사용량·복구 기록 보존, 기기 키·설정 보존을 확인했다. 현금흐름의 기존 규칙·금액·상태·감사 시각을 보존한 첫 발생 건 이관, 금액 미정 허용, 0·음수 거절, 사용자 소유권, 중복 연결 거절, 할부 구매 계획과 소비 분류 저장을 검증했다. S15P31E103-145의 업종 컬럼 이름 변경 시 기존 값·인덱스·일정 이름 보존과 새 상품 정보의 NULL 유지도 확인했다. 기존 버전 체크섬 보존과 재실행 시 추가 적용 없음도 확인했다. 현행 구조는 25개 테이블·325개 컬럼·FK 41개이며 컬럼 사전과 SQL 정의를 대조했다.

BE 폴더에서 실행한다.

```powershell
.\gradlew.bat test --tests "com.yamu.be.global.schema.*"
```

- 테스트 원본: [InitialSchemaMigrationTest.java](../../../be/src/test/java/com/yamu/be/global/schema/InitialSchemaMigrationTest.java), [PaymentLimitUsageMigrationTest.java](../../../be/src/test/java/com/yamu/be/global/schema/PaymentLimitUsageMigrationTest.java), [DeviceSigningKeyMigrationTest.java](../../../be/src/test/java/com/yamu/be/global/schema/DeviceSigningKeyMigrationTest.java), [CashflowMigrationTest.java](../../../be/src/test/java/com/yamu/be/global/schema/CashflowMigrationTest.java), [CategoryProductMigrationTest.java](../../../be/src/test/java/com/yamu/be/global/schema/CategoryProductMigrationTest.java). MySqlTestSupport의 격리 MySQL 8.4.11 컨테이너에 Flyway를 적용한다. 빈 DB에 최신 구조를 생성하는 경우와 기존 데이터를 이름 변경·기기 키 필수화·현금흐름 보완·물품 코드/상품명 추가 버전으로 올리는 경우를 확인한다. 재현 명령은 `./gradlew.bat --no-daemon test --rerun-tasks`다. 실행 중 Windows 소켓 오류로 중단된 첫 전체 실행은 통과로 간주하지 않았으며 새 Gradle 프로세스로 전체를 다시 실행해 XML의 실제 결과를 확인했다.
- Docker가 없으면 DB 테스트가 생략될 수 있다. BUILD SUCCESSFUL만으로 통과를 판단하지 않고 `be/build/test-results/test/TEST-*.xml`에서 각 테스트의 tests·failures·errors·skipped를 확인한다.
- 이 테스트는 JPA 업무 엔티티 전체 매핑, 실제 POS 인증·혜택 계산, 등록 초기화 롤백, 동시 결제·취소·재시도, 금융망 연동을 검증하지 않는다. 해당 서비스를 구현할 때 별도 검증한다.
- 테스트 중 운영·개발자 로컬 DB를 초기화하지 않는다. MySQL DDL의 중간 실패는 전체 롤백이 보장되지 않으므로 데이터 삭제·Flyway clean/repair를 자동 실행하지 않는다.
- CHECK 위반 3819·필수값 누락 1364는 Spring에서 UncategorizedSQLException으로 전달될 수 있다. 기존 테스트는 실제 SQL 오류 코드로 제약 거절을 확인하며 연결·문법 오류를 통과시키지 않는다.
- 기존 공통 감사 엔티티와 다른 DB 테스트의 DataSource를 임의 수정하지 않는다. 현재 스키마 테스트는 컨테이너 재시작 시 캐시된 컨텍스트가 재사용되지 않도록 분리돼 있다.

과거 조사와 날짜별 검증 기록은 [이전 검토 기록](archive/initial-review.md)에 보관한다. 정책 원본의 이름과 위치는 [스키마 안내](README.md)의 정책 표를 따른다.
