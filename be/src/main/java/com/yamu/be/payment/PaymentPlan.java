package com.yamu.be.payment;

/** 결제 방식. 값 이름은 API 명세 V2 「POS 결제 요청 생성」의 {@code plan}과 같다(POL-013). */
public enum PaymentPlan {
    /** 일시불. 개월 수를 받지 않고 DB에는 1로 저장한다 */
    LUMP_SUM,
    /** YAMU 모의 무이자 할부. 신용카드만, 2~12개월, 50,000원 이상 */
    INSTALLMENT
}
