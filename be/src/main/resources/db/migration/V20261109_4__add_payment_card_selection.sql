-- 결제 상세의 selectedCard.recommendedRank·userSelected. 승인 응답의 값은 Redis 시도 기록에만 있어 보관이 끝나면 사라졌다.
-- 승인 시작(PROCESSING 선점) 때 카드와 함께 적고, 재승인하면 카드와 함께 덮어쓴다. 이 칸이 생기기 전 결제와 SEED는 NULL이다.
ALTER TABLE payment_transaction
    ADD COLUMN recommended_rank SMALLINT NULL COMMENT '승인한(마지막 시도) 카드의 추천 순위, 1부터. 추천 밖 카드·기록 전 결제·SEED는 NULL'
        AFTER card_product_id,
    ADD COLUMN user_selected BOOLEAN NULL COMMENT '마지막 시도의 카드를 사용자가 직접 골랐는지. 기록 전 결제·SEED는 NULL'
        AFTER recommended_rank,
    ADD CONSTRAINT ck_payment_recommended_rank CHECK (recommended_rank IS NULL OR recommended_rank >= 1);
