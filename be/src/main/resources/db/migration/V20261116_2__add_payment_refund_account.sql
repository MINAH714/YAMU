-- 체크카드 취소 환불을 넣은 계좌(S15P31E103-323). 전에는 저장하지 않아 환급 내역이 카드의 "지금" 연결 계좌를 보여 줬고,
-- 사용자가 계좌를 바꾸면 실제 입금 계좌와 달라졌다. 신용카드 환급은 refund.linked_account_id에 따로 저장한다.
ALTER TABLE payment_transaction
    ADD COLUMN refund_linked_account_id BIGINT NULL COMMENT '체크카드 취소 환불을 입금한 계좌. 0원 승인·신용카드·취소 전은 NULL'
        AFTER fin_transaction_no;

-- 이 칸이 생기기 전에 취소된 체크카드 결제는 카드의 지금 연결 계좌로 한 번 채워 고정한다(2026-11-16 결정).
-- 그때 넣은 계좌는 대부분 지금 연결 계좌와 같고, 채워 두면 앞으로 계좌를 바꿔도 따라 바뀌지 않는다.
-- 카드를 해제해 연결 계좌가 없는 결제는 NULL로 남는다. 환급 내역 조회와 같은 조건이다(YAMU 결제, 승인액 > 0).
UPDATE payment_transaction t
    JOIN card_product p ON p.card_product_id = t.card_product_id AND p.card_type = 'CHECK'
    JOIN user_card c ON c.user_card_id = t.user_card_id AND c.user_id = t.user_id
   SET t.refund_linked_account_id = c.linked_account_id
 WHERE t.status = 'CANCELED'
   AND t.source = 'YAMU'
   AND t.approved_amount > 0
   AND t.refund_linked_account_id IS NULL;

-- 다른 사용자의 계좌가 들어가지 않게 refund와 같은 (계좌, 사용자) 외래 키를 건다.
ALTER TABLE payment_transaction
    ADD KEY ix_payment_refund_account_owner (refund_linked_account_id, user_id),
    ADD CONSTRAINT fk_payment_refund_account_owner FOREIGN KEY (refund_linked_account_id, user_id)
        REFERENCES linked_account (linked_account_id, user_id);
