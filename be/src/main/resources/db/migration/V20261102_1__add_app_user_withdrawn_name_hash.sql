-- 탈퇴한 사람의 재가입 본인 확인(S15P31E103-214, !55 리뷰 반영). 임시 결정.
-- 탈퇴하면 이름(name)을 지우므로 재가입 때 번호·생년월일만으로 찾으면, 두 값만 아는 다른 사람이 재가입을 먼저 확정해
-- 본인이 영구히 막히고 탈퇴 여부도 드러난다. 탈퇴 때 이름의 조회 해시(keyed HMAC-SHA-256, phone_hash와 같은 키)만 남겨
-- 시드 사용자처럼 이름·생년월일·번호가 모두 맞아야 재가입되게 한다. 이름 원문은 남기지 않는다.
--   탈퇴한 행에만 값이 있고, 재가입으로 열쇠가 새 사용자에게 옮겨지면 phone_hash·birth_date와 함께 비운다.
ALTER TABLE app_user
    ADD COLUMN withdrawn_name_hash BINARY(32) NULL
        COMMENT '탈퇴 때 남긴 이름 조회용 keyed HMAC-SHA-256(앞뒤 공백 제거 후); 재가입 본인 확인용, 탈퇴 행에만' AFTER deleted_at;
