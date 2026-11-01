package com.yamu.be.payment.pos;

import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;

/**
 * {@code payment_transaction.merchant_context_snapshot}에 JSON으로 두는 최초 거래 조건 근거.
 * 서버가 확인한 값만 담고, 이후 POS 설정이 바뀌어도 과거 결제의 조건은 이 값을 쓴다(10/26 결정).
 *
 * @param version 이 JSON 모양의 버전. 칸을 더하면 올린다
 */
public record MerchantContextSnapshot(
        int version,
        String posCode,
        String brandCode,
        String merchantName,
        String categoryCode,
        PaymentChannel channel,
        FacilityType facilityType,
        String orderNo,
        long amount,
        PaymentPlan plan,
        Integer installmentMonths) {

    static final int CURRENT_VERSION = 1;
}
