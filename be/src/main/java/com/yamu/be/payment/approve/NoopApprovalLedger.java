package com.yamu.be.payment.approve;

import com.yamu.be.payment.domain.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;

/**
 * 원장 구현({@code JdbcApprovalLedger})이 없을 때만 쓰는 빈 원장. 아무 표도 쓰지 않는다.
 * 혜택·실적·한도 합계는 승인 뒤에도 바뀌지 않는다.
 */
@Slf4j
public class NoopApprovalLedger implements ApprovalLedger {

    @Override
    public Recorded recordApproval(PaymentTransaction payment, ApprovalOutcome.Approved outcome) {
        log.debug("원장 반영 생략 paymentId={}", payment.getId());
        return Recorded.none();
    }
}
