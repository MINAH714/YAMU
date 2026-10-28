-- 카드사 기준 카드 상태. YAMU 등록 상태(status)와 별개로, 카드사가 정지했거나 유효기간이 지난 카드를 구분한다.
-- 결제·예정 지출이 정지·만료 카드를 거절할 때 쓴다. 시연에서는 시드 데이터로 SUSPENDED·EXPIRED 카드를 미리 넣는다.
-- 기존 카드 행은 기본값 NORMAL로 채워진다.
-- 두 상태가 나란히 있어 헷갈리지 않도록 status의 COMMENT도 "YAMU 등록 상태"로 고친다(타입·NULL·기본값은 그대로).
ALTER TABLE user_card
    MODIFY COLUMN status VARCHAR(20) NOT NULL
        COMMENT 'YAMU 등록 상태: PENDING 등록 전 / ACTIVE 추천·결제 사용 / INACTIVE 추천 제외 / UNREGISTERED 등록 해제; 카드사 상태는 issuer_status',
    ADD COLUMN issuer_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        COMMENT '카드사(발급사)가 정한 상태: NORMAL 정상 / SUSPENDED 정지 / EXPIRED 유효기간 만료; YAMU 등록 상태는 status' AFTER status,
    ADD CONSTRAINT ck_card_issuer_status CHECK (issuer_status IN ('NORMAL','SUSPENDED','EXPIRED'));
