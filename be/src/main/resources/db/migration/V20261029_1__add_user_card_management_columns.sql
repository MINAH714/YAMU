-- 카드 관리(API 명세 V2 「보유 카드 목록 조회」「카드 정보 수정」「카드 고정 정렬 순서 저장」)에 필요한 칸.
-- 표시 순서: 사용자가 정한 카드 목록 순서이자 추천 동률의 마지막 기준(FIXED_ORDER, DEC-004). 순서를 저장하기 전에는 NULL(등록 순).
-- 기본 카드: 사용자당 최대 1장(DEC-004). 등록된 카드(ACTIVE·INACTIVE)만 지정할 수 있다.
--   "사용자당 1장"은 기본 카드일 때만 user_id가 들어가는 생성 칼럼의 UNIQUE로 DB가 지킨다.
-- 결제일 변경 시각: 직전 변경 후 3개월 안에는 다시 바꿀 수 없다(FR-067). 등록 때 정한 결제일은 변경으로 보지 않는다.
ALTER TABLE user_card
    ADD COLUMN display_order INT NULL
        COMMENT '보유 카드 목록 표시 순서(작을수록 앞)이자 추천 동률의 마지막 기준; 저장 전 NULL은 등록 순' AFTER registered_at,
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '기본 카드 여부(순혜택이 같을 때만 우선); 사용자당 최대 1장, 등록된 카드만' AFTER display_order,
    ADD COLUMN default_owner_id BIGINT AS (CASE WHEN is_default = 1 THEN user_id END) STORED
        COMMENT '기본 카드면 user_id, 아니면 NULL; 사용자당 기본 카드 1장 UNIQUE용 생성 칼럼' AFTER is_default,
    ADD COLUMN payment_day_changed_at TIMESTAMP(6) NULL
        COMMENT 'UTC 마지막 결제일 변경 시각; 3개월 재변경 제한 기준, 등록 때 정한 결제일은 NULL' AFTER default_owner_id,
    ADD UNIQUE KEY uk_card_default_owner (default_owner_id),
    ADD CONSTRAINT ck_card_default_flag CHECK (is_default IN (0, 1)),
    ADD CONSTRAINT ck_card_default_registered CHECK (is_default = 0 OR status IN ('ACTIVE','INACTIVE')),
    ADD CONSTRAINT ck_card_display_order CHECK (display_order IS NULL OR display_order >= 0);
