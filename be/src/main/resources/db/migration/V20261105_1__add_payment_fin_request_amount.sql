-- S15P31E103-292: 체크카드 출금 요청액을 요청 번호와 함께 남긴다.
-- 즉시할인이 있으면 출금액이 requested_amount보다 작아서, 결과를 모르는 출금을 거래내역에서 확인할 때 이 값으로 비교한다.
ALTER TABLE payment_transaction
    ADD COLUMN fin_request_amount BIGINT NULL
        COMMENT '이번 시도의 금융망 출금 요청액(결제 금액 - 즉시할인); fin_request_no와 함께 저장·삭제, 출금 요청 전 NULL'
        AFTER fin_request_no,
    ADD CONSTRAINT ck_payment_fin_request_amount CHECK (fin_request_amount IS NULL OR fin_request_amount > 0);
