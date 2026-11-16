# 계좌 이력 컬럼 사전 — S15P31E103-517

원본: V20261116_1__create_account_history.sql. 기존 계좌번호·금융망 키는 복제하지 않는다.
모든 금액은 signed BIGINT 원화, 저장 시각은 UTC, 날짜는 한국 날짜다. 모든 PK는 BIGINT AUTO_INCREMENT다.

| 테이블 | 컬럼 | 의미 |
|---|---|---|
| account_history_stream | stream_id, linked_account_id, user_id | 계좌·동일 사용자 복합 FK |
| | source VARCHAR(20) | SSAFY / QA_SYNTHETIC |
| | dataset_key VARCHAR(64) NULL | 실제는 NULL, 합성은 필수. qa_seed_member(dataset_key,user_id) 복합 FK |
| | generation BIGINT | 동기화 시작마다 증가. 늦게 도착한 이전 실행은 SUPERSEDED |
| | active BOOLEAN | 조회 선택 여부. QA는 검증 후 활성화 |
| | range_from, range_to DATE NULL | QA 적용 구간. 실제 stream은 NULL |
| | input_hash VARCHAR(64) NULL | QA 입력·생성 행·고정 실제 경계의 SHA-256 |
| | created_at, updated_at TIMESTAMP(6), version BIGINT | 감사 및 JPA 낙관적 버전 |
| account_transaction | transaction_id, stream_id | 원장 행과 출처 FK |
| | external_id VARCHAR(128) ASCII binary | stream 안 UNIQUE. 실제 금융망 거래 번호 또는 QA 이벤트 ID |
| | occurred_at DATETIME(6) | UTC 거래 시각 |
| | direction VARCHAR(12) | DEPOSIT / WITHDRAWAL |
| | amount, balance_after BIGINT | 금액(0 이상), 거래 후 잔액 |
| | content_hash CHAR(64), created_at TIMESTAMP(6) | 정규화 거래 내용 SHA-256과 최초 저장 시각 |
| account_history_sync_run | run_id, stream_id, generation | 실행 및 시작 세대 |
| | range_from, range_to DATE | 요청한 전체 조회 기간 |
| | status VARCHAR(40) | RUNNING, OK, NO_ANCHOR, SUPERSEDED, SOURCE_CONFLICT, 기존 복원·외부 실패 사유 등 |
| | reported_count, received_count BIGINT NULL | 보고 건수와 받은 건수. 실패는 0건으로 바꾸지 않음 |
| | queried_at DATETIME(6) NULL | 금융망 응답 조회 시각. 은행의 원자적 스냅샷 보장은 아님 |
| | created_at, updated_at TIMESTAMP(6), version BIGINT | 감사·버전 |
| account_history_coverage | coverage_id, stream_id, run_id | 완전 조회 근거. run_id UNIQUE, 실행과 stream 복합 FK |
| | range_from, range_to DATE | 검증된 조회 범위. 최초 거래 이전 존재 여부를 추정하지 않도록 시작일 제한 |
| | valid BOOLEAN | 충돌·늦은 신규 거래로 종속 근거 무효화 시 false |
| | opening_balance BIGINT NULL | 이 범위 시작 전 일말 잔액. NULL은 근거 없음 |
| | opening_evidence VARCHAR(24) | NONE / FIRST_TRANSACTION / PREVIOUS_COVERAGE / QA_SCENARIO |
| | opening_transaction_id BIGINT NULL | 첫 거래 전 잔액 역산 근거. 같은 stream의 거래를 복합 FK로 참조 |
| | opening_coverage_id BIGINT NULL | 바로 전 날짜까지 완전 조회된 범위. 같은 stream의 coverage를 복합 FK로 참조 |
| | created_at, updated_at TIMESTAMP(6), version BIGINT | 감사·버전 |

조회 기간 순서, source/dataset 조합, 금액/방향/세대는 CHECK로 검사한다. QA 소유권 복합 FK를 위해 기존 qa_seed_member에 (dataset_key,user_id) UNIQUE를 추가한다. 같은 계좌·source는 한 stream이다. QA 시나리오는 자산 전용 reset 후 새 stream으로 재생성한다.

실제 외부 ID 128자는 임시 기술 상한이다. 초과하거나 ASCII 범위를 벗어난 ID는 잘라 저장하지 않고 HISTORY_INCOMPLETE로 거절한다. 공식 금융망 길이 계약 전수 확인 완료를 의미하지 않는다.

일별 결과는 저장 원장·유효 coverage로 재계산한다. 별도 일별 캐시와 보관기간 자동 삭제는 도입하지 않는다.

별도 앵커 테이블은 없다. 시작 잔액과 근거 종류·참조를 coverage 한 곳에서 관리한다. 종료 잔액은 원장에서 재계산하며 중복 저장하지 않는다. 근거 종류에 맞는 잔액·참조의 NULL 조합은 CHECK로 강제한다. 이전 coverage를 사용할 때 종료일이 다음 범위의 시작 전날인지와 재계산 성공을 검사한다. 중간 미조회 기간을 건너뛰지 않는다. QA 시작 잔액은 시나리오 입력이며 실제 최초 거래 전 잔액과 합성 종료 잔액을 별도로 비교한다.
