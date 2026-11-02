package com.yamu.be.payment.domain;

/** 서버가 확인한 거래 채널(DB {@code payment_transaction.payment_channel}). 인증된 POS의 설정에서 온다. */
public enum PaymentChannel {
    ONLINE,
    OFFLINE,
    /** 미확인. 혜택의 채널 조건을 임의로 충족한 것으로 보지 않는다 */
    UNKNOWN
}
