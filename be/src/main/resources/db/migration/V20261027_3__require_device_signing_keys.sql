-- All devices sign authentication challenges, including PIN-only devices.
-- Fail before changing nullability if an existing device has no key.
-- Do not fabricate keys or delete devices; complete key registration first.
ALTER TABLE user_device
    ADD CONSTRAINT ck_device_required_key_preflight
    CHECK (public_key IS NOT NULL AND key_algorithm IS NOT NULL);

ALTER TABLE user_device
    DROP CHECK ck_device_required_key_preflight,
    DROP CHECK ck_device_key_pair,
    DROP CHECK ck_device_biometric,
    MODIFY COLUMN public_key TEXT NOT NULL COMMENT '현재 서명 검증용 공개키; PIN 전용 포함 모든 기기 필수',
    MODIFY COLUMN key_algorithm VARCHAR(32) NOT NULL COMMENT '서명 검증 알고리즘; 모든 기기 필수',
    ADD CONSTRAINT ck_device_biometric CHECK (biometric_enabled IN (0,1));
