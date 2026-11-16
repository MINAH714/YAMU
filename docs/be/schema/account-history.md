# 계좌 이력 컬럼 사전 — S15P31E103-517

## QA 시작 자금 전환 근거 (550)

`V20261116_4__add_qa_funding_boundary.sql`은 실제 시작 자금 입금으로 합성 종료 잔액을 마련한 경우에만 사용하는 QA 전용 연결이다. 실제 잔액·입출금·coverage는 변경하지 않는다.

550의 초기 파일 번호 `20261116.3`은 체크카드 환불 계좌 마이그레이션과 충돌하여, 운영 적용 전 `20261116.4`로 조정했다. SQL 본문과 환불 계좌 파일은 유지한다. 운영은 조정 전 `20261112.2`까지 적용된 상태로 확인했다. 다른 환경에서 기존 자금 연결 파일을 이미 적용했다면 그대로 배포하거나 Flyway `repair`를 실행하지 말고, 적용 이력과 백업을 먼저 확인한다.

| 테이블 | 컬럼 | 의미 |
|---|---|---|
| qa_seed_funding_boundary | qa_stream_id BIGINT PK/FK | 합성 stream 하나당 명시적으로 선택한 전환 근거 하나 |
| | actual_stream_id BIGINT | 같은 사용자·같은 계좌의 SSAFY stream. 쓰기와 읽기에서 소유권 검사 |
| | funding_transaction_id BIGINT | 실제 시작 자금 입금 거래. actual_stream_id와 복합 FK |
| | funding_content_hash CHAR(64) | 등록 당시 실제 거래 내용 해시. 변경되면 연결 불인정 |
| | funded_balance BIGINT | 입금 후 양수 잔액. 합성 종료 잔액과 동일해야 함 |

전환일은 합성 마지막 날 다음 날이며 실제 최초 거래 날짜여야 한다. 유효한 FIRST_TRANSACTION coverage와 실제 기초잔액 0원을 요구한다. 같은 날 가입 인증 등 준비 거래가 먼저 있었다면, 준비가 끝난 명시적 입금 거래의 잔액을 사용한다. 현재 잔액이나 나중 날짜 입금을 근거로 과거를 변경하지 않는다. 실제 조회 충돌로 coverage가 무효화되면 합성 구간도 숨긴다. 실제 입금은 보존하며 합성 잔액에 다시 더하지 않는다. 이 표는 실제 원장을 대체하는 잔액 앵커가 아닌 QA 원장 전환의 대응 근거다.

자산 전용 reset은 이 대응 근거와 합성 stream만 제거한다. 실제 거래 FK는 보존되며 기존 사용자 전체 reset의 실제 이력 보존 차단을 유지한다.

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
