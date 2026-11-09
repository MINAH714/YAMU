-- 보안 설정(API 명세 V2 「보안 설정 수정」 No.26, FR-071·FR-072, S15P31E103-353).
-- 앱 마이 → 보안에서 바꾸는 두 값을 저장한다. 기존 사용자는 명세 기본값으로 시작한다.
--   auto_lock_seconds: 앱을 떠난 뒤 다시 잠그기까지(0 즉시 · 60 기본 · 300)
--   barcode_auth_method: 결제 코드를 열 때 인증 방식(BIOMETRIC_OR_PIN 기본 · PIN_ONLY)
-- 명세의 setupGraceSeconds(300)·barcodeAuthGraceSeconds(60)는 앱이 바꾸지 않아 저장하지 않는다(임시).
-- V20261106_3이 만든 payment_auto_select 뒤에 칸을 붙이므로 그 파일보다 뒤에 적용돼야 한다. 번호가 develop에서 가장 커서 그렇게 된다.
ALTER TABLE app_user
    ADD COLUMN auto_lock_seconds INT NOT NULL DEFAULT 60
        COMMENT '앱을 떠난 뒤 다시 잠그기까지(초); 0·60·300' AFTER payment_auto_select,
    ADD COLUMN barcode_auth_method VARCHAR(20) CHARACTER SET ascii NOT NULL DEFAULT 'BIOMETRIC_OR_PIN'
        COMMENT '결제 코드를 열 때 인증 방식; BIOMETRIC_OR_PIN · PIN_ONLY' AFTER auto_lock_seconds,
    ADD CONSTRAINT ck_user_auto_lock_seconds CHECK (auto_lock_seconds IN (0, 60, 300)),
    ADD CONSTRAINT ck_user_barcode_auth_method CHECK (barcode_auth_method IN ('BIOMETRIC_OR_PIN', 'PIN_ONLY'));
