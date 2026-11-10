-- S15P31E103-358: 가계부 항목 등록(POST /api/cashflow-items)의 Idempotency-Key 기록.
-- 키를 보낸 등록만 한 줄 남긴다. 같은 사용자·같은 키는 한 줄이라 동시 재시도·네트워크 재전송이 두 항목을 만들지 않는다.
-- 항목을 지워도 이 줄은 남겨, 늦게 온 재시도가 지운 항목을 다시 만들지 않게 한다(Codex 검토). 지운 항목의 내용은 남기지 않으려고
-- 키·지문·항목 번호만 둔다. 항목은 지워질 수 있어 cashflow_item_id에 외래 키를 두지 않는다.
-- request_fingerprint는 처음 요청 내용의 SHA-256이다. 같은 키로 다른 내용을 보내면 409 IDEMPOTENCY_KEY_REUSED로 가린다.
CREATE TABLE cashflow_create_request (
    cashflow_create_request_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '등록 요청 기록 번호',
    user_id BIGINT NOT NULL COMMENT '등록한 사용자',
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '등록 Idempotency-Key(UUID, 소문자)',
    request_fingerprint BINARY(32) NOT NULL COMMENT '처음 등록 요청 내용의 SHA-256; 같은 키 재요청 비교',
    cashflow_item_id BIGINT NOT NULL COMMENT '그 요청으로 만든 규칙; 규칙을 지워도 번호는 남김(외래 키 없음)',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 생성 시각',
    PRIMARY KEY (cashflow_create_request_id),
    UNIQUE KEY uk_cashflow_create_request (user_id, request_key),
    CONSTRAINT fk_cashflow_create_request_user FOREIGN KEY (user_id) REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='가계부 항목 등록 Idempotency-Key 기록';
