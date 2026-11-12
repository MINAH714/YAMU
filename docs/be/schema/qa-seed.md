# QA 시드 소유권 — S15P31E103-442

V20261112_1__add_qa_seed_ownership.sql이 추가한 두 테이블의 컬럼 사전이다.
생성기는 업무 API/인증을 우회하는 HTTP 경로를 추가하지 않고 명시적 CLI로만 실행된다.

| 테이블 | 컬럼 | 타입/제약 | 의미 |
|---|---|---|---|
| qa_seed_dataset | dataset_key | VARCHAR(64), ASCII binary, PK | QA 세트 식별자 |
| qa_seed_dataset | seed_version | INT NOT NULL | 생성 규격 버전(현재 1) |
| qa_seed_dataset | base_date | DATE NOT NULL | 한국 기준일 |
| qa_seed_dataset | input_hash | BINARY(32) NOT NULL | 전체 정규화 JSON 입력의 도메인 분리 HMAC; 비밀 원문 저장 안 함 |
| qa_seed_dataset | run_id | CHAR(36) NOT NULL | 생성 실행 UUID, 재생성 시 변경 |
| qa_seed_dataset | created_at | TIMESTAMP(6) NOT NULL | UTC 생성 시각 |
| qa_seed_member | dataset_key | VARCHAR(64), FK NOT NULL | 소유 세트 |
| qa_seed_member | persona_key | VARCHAR(32) NOT NULL | 세트 안 페르소나 식별자 |
| qa_seed_member | user_id | BIGINT, UNIQUE, FK NOT NULL | 명시적으로 소유한 QA 사용자 |
| qa_seed_member | enriched | BOOLEAN NOT NULL DEFAULT FALSE, CHECK 0/1 | 가입 후 보강 완료 여부 |

qa_seed_member의 PK는 (dataset_key, persona_key). FK는 각각 qa_seed_dataset과 app_user를 참조하고 기본 RESTRICT다.
모든 사용자/카드 생성과 소유권 기록을 같은 트랜잭션으로 묶는다. 기존 사용자를 소유권 표에 편입하지 않는다.
추가 앱 사용 데이터는 사용자 소유 관계로 정리하므로 최초 생성 행만 추적하는 방식보다 QA 후 정리에 적합하다.
공용 상품·혜택·가맹점은 관리 대상이 아니다. 실패한 생성은 소유권까지 롤백하며 로그에는 비밀값을 남기지 않는다.

[실행·초기화·삭제와 한계](../../../be/seed/qa/README.md)
