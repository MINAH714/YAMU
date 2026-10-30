package com.yamu.be.card.domain;

/** 카드 종류(DB {@code card_product.card_type}). 신용카드는 결제일에 청구액을, 체크카드는 결제 즉시 연결 계좌에서 출금한다. */
public enum CardType {
    CREDIT,
    CHECK
}
