-- 카드사 기준 카드 상태. 사용자 등록 상태(status)와 별개로, 카드사가 정지했거나 유효기간이 지난 카드를 구분한다.
-- 결제·예정 지출이 정지·만료 카드를 거절할 때 쓴다. 시연에서는 시드 데이터로 SUSPENDED·EXPIRED 카드를 미리 넣는다.
-- 기존 카드 행은 기본값 NORMAL로 채워진다.
ALTER TABLE user_card
    ADD COLUMN issuer_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL: 정상 / SUSPENDED: 카드사 정지 / EXPIRED: 유효기간 만료' AFTER status,
    ADD CONSTRAINT ck_card_issuer_status CHECK (issuer_status IN ('NORMAL','SUSPENDED','EXPIRED'));
