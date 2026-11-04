package com.yamu.be.payment.cancel;

import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;

/**
 * 임시 취소 원장 복구(Task 5.2.5 구현 전). 승인 원장도 Noop이라 되돌릴 실적·한도 기록이 없다.
 * 복구하지 않았으므로 {@code performance_reversed_at}도 적지 않는다.
 */
@Slf4j
public class NoopCancellationLedger implements CancellationLedger {

    @Override
    public PaymentCancelResponse.Restored reverse(PaymentTransaction payment, LocalDateTime canceledAt) {
        log.debug("취소 원장 복구 생략(임시) paymentId={}", payment.getId());
        return PaymentCancelResponse.Restored.none();
    }
}
