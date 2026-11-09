-- Keep the merged initial migration unchanged; update only column documentation.
-- Existing IDs, VARCHAR(255), NULL allowance and the unique key are preserved.
ALTER TABLE app_user
    MODIFY COLUMN ssafy_member_id VARCHAR(255) NULL
    COMMENT '서버 생성 금융망 userId; y{랜덤 9자리}@yamu.app(19자, SSAFY 40자 제한), 최초 생성 후 재사용, 메일 인증 없음';
