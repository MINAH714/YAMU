package com.yamu.be.payment.domain;

/** 결제가 어디서 생겼는지(DB {@code payment_transaction.source}). */
public enum TransactionSource {
    /** 앱·POS로 한 모의 결제 */
    YAMU,
    /** 카드 등록 전 실적·사용량을 맞추려고 시드로 넣은 거래(POL-008, FR-070) */
    SEED
}
