package com.yamu.be.payment.approve;

import com.yamu.be.payment.domain.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;

/** 임시 원장 반영(Task 5.2.5 구현 전). 아무 표도 쓰지 않는다. 혜택·실적·한도 합계는 승인 뒤에도 바뀌지 않는다. */
@Slf4j
public class NoopApprovalLedger implements ApprovalLedger {

    @Override
    public void recordApproval(PaymentTransaction payment, ApprovalOutcome.Approved outcome) {
        log.debug("원장 반영 생략(임시) paymentId={}", payment.getId());
    }
}
