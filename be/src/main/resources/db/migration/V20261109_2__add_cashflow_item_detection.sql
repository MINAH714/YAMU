-- S15P31E103-349: 결제 이력에서 찾은 반복 지출 후보(CANDIDATE)를 저장한다.
-- source는 명세 「가계부 항목 목록」의 MANUAL(사용자 입력)·DETECTED(결제 이력 탐지)다. 기존 행은 모두 사용자 입력이다.
-- detected_user_card_id·detected_merchant_id·detected_amount는 탐지 키(같은 카드·가맹점·승인 금액)이고, 탐지가 만든 시리즈의
-- 첫 규칙에만 있다. 사용자가 후보의 카드·금액을 고쳐도 키는 바뀌지 않는다(user_card_id·amount 칸과 따로 둠).
-- UNIQUE로 같은 후보가 동시 요청·재탐지로 두 번 생기지 않게 하고, 무시(IGNORED)·확인(CONFIRMED)한 후보도 다시 만들지 않는다.
-- 사용자 입력 행은 키가 NULL이라 UNIQUE에 걸리지 않는다.
-- 가맹점 외래 키는 두지 않는다. 가맹점 카탈로그를 다시 넣을 때 막히지 않게 하고, 키는 같은 가맹점인지 비교하는 데만 쓴다.
ALTER TABLE cashflow_item
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 사용자 입력 / DETECTED 결제 이력 탐지',
    ADD COLUMN detected_user_card_id BIGINT NULL COMMENT '탐지 키: 반복 결제의 카드; 탐지 시리즈 첫 규칙만',
    ADD COLUMN detected_merchant_id BIGINT NULL COMMENT '탐지 키: 반복 결제의 가맹점; 탐지 시리즈 첫 규칙만',
    ADD COLUMN detected_amount BIGINT NULL COMMENT '탐지 키: 반복 결제의 승인 금액; 탐지 시리즈 첫 규칙만',
    ADD UNIQUE KEY uk_cashflow_item_detection (user_id, detected_user_card_id, detected_merchant_id, detected_amount),
    ADD KEY ix_cashflow_detected_card_owner (detected_user_card_id, user_id),
    ADD CONSTRAINT fk_cashflow_detected_card_owner FOREIGN KEY (detected_user_card_id, user_id)
        REFERENCES user_card (user_card_id, user_id),
    ADD CONSTRAINT ck_cashflow_source CHECK (source IN ('MANUAL','DETECTED')),
    ADD CONSTRAINT ck_cashflow_detection_key CHECK (
        (detected_user_card_id IS NULL AND detected_merchant_id IS NULL AND detected_amount IS NULL)
        OR (source = 'DETECTED' AND detected_user_card_id IS NOT NULL AND detected_merchant_id IS NOT NULL
            AND detected_amount > 0));
