-- S15P31E103-148: 생체 로그인용 공개키를 기기 키와 따로 둔다.
-- Android Keystore 키의 "쓸 때마다 지문 필요" 옵션은 키를 만들 때 고정된다.
--   - public_key(기기 키): 지문 없이 서명. PIN 로그인에서 "등록된 그 폰"을 증명한다.
--   - biometric_public_key(생체 키): 지문을 찍어야 서명. 생체 로그인에서 "그 폰 + 그 사람"을 함께 증명한다.
-- 키 하나로는 PIN 로그인에 지문을 요구하지 않으면서 생체 로그인을 증명할 수 없다.
-- 두 키의 알고리즘은 key_algorithm 하나를 같이 쓴다(EC_P256).
-- 생체를 켠 기존 기기는 생체 키가 없으므로 아래 제약에서 실패한다. 키를 만들어 넣거나 생체를 끄지 않고, 키 등록부터 마친다.
ALTER TABLE user_device
    ADD COLUMN biometric_public_key TEXT NULL
        COMMENT '생체 로그인 서명 검증용 공개키(지문 인증이 필요한 키); 생체 사용 시 필수, 지문 재등록 시 교체'
        AFTER key_algorithm;

ALTER TABLE user_device
    DROP CHECK ck_device_biometric,
    ADD CONSTRAINT ck_device_biometric CHECK (
        biometric_enabled IN (0,1)
        AND (biometric_enabled = 0 OR biometric_public_key IS NOT NULL));
