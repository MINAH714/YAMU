-- S15P31E103-301: 결제 메모 API의 memoUpdatedAt. updated_at은 승인·취소 때도 바뀌어 메모 시각이 아니다.
ALTER TABLE payment_transaction
    ADD COLUMN memo_updated_at TIMESTAMP(6) NULL COMMENT 'UTC 메모를 남기거나 지운 마지막 시각; 메모 API만 바꿈, 메모를 고친 적 없으면 NULL'
        AFTER memo;
