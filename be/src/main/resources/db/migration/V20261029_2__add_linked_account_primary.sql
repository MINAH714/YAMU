-- 기준 계좌(API 명세 V2 「계좌 연결」「계좌 목록 조회」「계좌 정보 수정 (기준 계좌 지정)」, FR-033).
-- 처음 연결한 계좌가 자동으로 기준 계좌가 되고, 사용자가 다른 계좌로 바꿀 수 있다. 홈의 기본 계좌 미리보기용이며
-- 잔고예보의 복수 계좌 선택(조회 때 accountIds로 보냄)과는 별개다.
--   "사용자당 1개"는 기준 계좌일 때만 user_id가 들어가는 생성 칼럼의 UNIQUE로 DB가 지킨다(user_card.is_default와 같은 방식).
--   연결된 계좌(CONNECTED)만 기준 계좌가 될 수 있다. 연결 해제 API가 생기면 해제 전에 기준 계좌를 옮겨야 한다.
ALTER TABLE linked_account
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '기준 계좌 여부(홈 기본 계좌 미리보기); 사용자당 최대 1개, 연결된 계좌만' AFTER status,
    ADD COLUMN primary_owner_id BIGINT AS (CASE WHEN is_primary = 1 THEN user_id END) STORED
        COMMENT '기준 계좌면 user_id, 아니면 NULL; 사용자당 기준 계좌 1개 UNIQUE용 생성 칼럼' AFTER is_primary,
    ADD UNIQUE KEY uk_account_primary_owner (primary_owner_id),
    ADD CONSTRAINT ck_account_primary_flag CHECK (is_primary IN (0, 1)),
    ADD CONSTRAINT ck_account_primary_connected CHECK (is_primary = 0 OR status = 'CONNECTED');

-- 이미 연결된 계좌가 있는 사용자는 지금까지 기준 계좌로 보던 "가장 먼저 연결한 계좌"를 그대로 기준 계좌로 둔다.
UPDATE linked_account la
    JOIN (SELECT user_id, MIN(linked_account_id) AS first_id
          FROM linked_account
          WHERE status = 'CONNECTED'
          GROUP BY user_id) f ON la.linked_account_id = f.first_id
SET la.is_primary = TRUE;
