package com.yamu.be.card.domain;

/** 카드사(발급사)가 정한 상태(DB {@code user_card.issuer_status}). YAMU 등록 상태({@link CardRegistrationStatus})와 별개로 정지·만료 카드를 구분한다. */
public enum CardIssuerStatus {
    NORMAL,
    /** 카드사 정지 */
    SUSPENDED,
    /** 유효기간 만료 */
    EXPIRED
}
