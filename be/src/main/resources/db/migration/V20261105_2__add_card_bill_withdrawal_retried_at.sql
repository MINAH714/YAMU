-- S15P31E103-207: 결제일 청구 출금의 재시도 표시.
-- 결과 불명 출금이 유예 뒤에도 거래내역에 없으면 새 요청 번호로 한 번만 다시 보낸다(2026-11-05 결정). 다시 보냈는지를 남겨,
-- 두 번째도 확인되지 않으면 더 보내지 않고 운영이 확인하게 한다. 실패 사유는 저장하지 않는다.
ALTER TABLE card_bill
    ADD COLUMN withdrawal_retried_at TIMESTAMP(6) NULL
        COMMENT 'UTC 첫 출금이 처리되지 않았다고 보고 새 요청 번호로 다시 보낸 시각; 다시 보내지 않았으면 NULL'
        AFTER failed_at;
