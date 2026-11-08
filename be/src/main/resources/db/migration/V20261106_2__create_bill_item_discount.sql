-- S15P31E103-299: 청구 확정 때 청구할인을 회차에 나눈 기록. 취소 환급·미납 제외 몫(회차 청구액 − 배분 할인)의 근거다.
-- 확정 때 한 번 쓰고 고치지 않는다. 같은 회차·혜택은 한 행뿐이다(재실행·동시 확정 방지는 청구서 잠금과 이 UNIQUE).
CREATE TABLE bill_item_discount (
    bill_item_discount_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회차별 청구할인 배분 번호',
    card_bill_id BIGINT NOT NULL COMMENT '할인을 확정한 청구서',
    bill_item_id BIGINT NOT NULL COMMENT '할인을 나눈 회차',
    payment_benefit_id BIGINT NOT NULL COMMENT '나눈 청구할인(승인 원장의 혜택 행)',
    amount BIGINT NOT NULL COMMENT '이 회차에 나눈 할인(원); 원금 비율, 원 단위 나머지는 마지막 회차',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 생성 시각(청구 확정 시각)',
    PRIMARY KEY (bill_item_discount_id),
    UNIQUE KEY uk_item_discount (bill_item_id, payment_benefit_id),
    KEY ix_discount_bill (card_bill_id),
    KEY ix_discount_benefit (payment_benefit_id),
    CONSTRAINT fk_discount_bill FOREIGN KEY (card_bill_id) REFERENCES card_bill (card_bill_id),
    CONSTRAINT fk_discount_item FOREIGN KEY (bill_item_id) REFERENCES bill_item (bill_item_id),
    CONSTRAINT fk_discount_benefit FOREIGN KEY (payment_benefit_id) REFERENCES payment_benefit (payment_benefit_id),
    CONSTRAINT ck_discount_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='회차별 청구할인 배분';
