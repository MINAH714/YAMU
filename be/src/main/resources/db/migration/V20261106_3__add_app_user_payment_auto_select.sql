-- 결제 설정(API 명세 V2 「결제 설정 조회」「결제 설정 수정」, DEC-004·FR-004·FR-065, S15P31E103-315).
-- 결제 카드 자동 선택: 켜면 결제마다 순혜택이 가장 큰 카드를 고르고, 끄면 항상 기본 카드(user_card.is_default)로 결제한다.
--   끄려면 기본 카드가 있어야 한다(409 DEFAULT_CARD_REQUIRED). 끈 상태에서 카드 수정·해제로 기본 카드가 없어지면 다시 켠다(임시).
--   기존 사용자는 켜진 상태(명세 기본값)로 시작한다.
ALTER TABLE app_user
    ADD COLUMN payment_auto_select BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT '결제 카드 자동 선택; 끄면 기본 카드로 결제(기본 카드가 있어야 끌 수 있음)' AFTER pin_updated_at,
    ADD CONSTRAINT ck_user_payment_auto_select CHECK (payment_auto_select IN (0, 1));
