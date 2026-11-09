-- S15P31E103-358: 가계부 항목 등록(POST /api/cashflow-items)의 Idempotency-Key.
-- 키를 보낸 등록만 저장한다. 같은 사용자·같은 키는 한 행이라 동시 재시도·네트워크 재전송이 두 항목을 만들지 않는다.
-- request_fingerprint는 처음 요청 내용의 SHA-256이다. 같은 키로 다른 내용을 보내면 409 IDEMPOTENCY_KEY_REUSED로 가린다.
-- 키는 등록한 첫 규칙에만 있고, 값을 고쳐 이어지는 규칙에는 옮기지 않는다. 키를 보내지 않은 등록은 둘 다 NULL이다.
ALTER TABLE cashflow_item
    ADD COLUMN request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '등록 Idempotency-Key(UUID); 보낸 등록만',
    ADD COLUMN request_fingerprint BINARY(32) NULL COMMENT '처음 등록 요청 내용의 SHA-256; 같은 키 재요청 비교',
    ADD UNIQUE KEY uk_cashflow_item_request (user_id, request_key),
    ADD CONSTRAINT ck_cashflow_request CHECK ((request_key IS NULL) = (request_fingerprint IS NULL));
