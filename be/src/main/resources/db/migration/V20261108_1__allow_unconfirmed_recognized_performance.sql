-- S15P31E103-310: 승인된 결제도 실적 인정액을 미확인(NULL)으로 저장할 수 있게 한다.
-- 인정액은 BE3 실적 인정 계산 값이다. 0은 약관상 실적 제외 확정, NULL은 미확인(인정월 규칙·최소 실적이 없는 상품, 거래 사실 부족)이다.
-- 실적 월은 계속 필수다(BE3가 정하지 않으면 결제가 승인월로 채운다).
ALTER TABLE payment_transaction
    DROP CHECK ck_payment_approval;

ALTER TABLE payment_transaction
    ADD CONSTRAINT ck_payment_approval CHECK (approved_at IS NULL OR (user_card_id IS NOT NULL AND approved_amount IS NOT NULL
        AND performance_month IS NOT NULL AND installment_months IS NOT NULL));
