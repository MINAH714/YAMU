package com.yamu.be.payment;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 확정 마감(DEC-003: POS 요청 뒤 60초) 판정과 EXPIRED 전환.
 *
 * <p>임시(명세 「결제 상태·예외 처리」 8장 Q4 제안): 마감이 지난 REQUESTED·DECLINED는 승인 요청·바코드 조회가 그 자리에서 EXPIRED로
 * 바꾼다. 바코드를 EXPIRED로 바꾸는 것은 부르는 쪽(승인·바코드 조회)이 한다. 아무도 조회하지 않은 결제를 정리하는 주기 작업은 Task 5.2.6 이후에 둔다.
 * 출금이 걸려 있을 수 있는 PROCESSING은 마감이 지나도 바꾸지 않는다(복구는 Task 5.2.3).
 *
 * <p>pos·barcode 패키지에 기대지 않는다(바코드 → 결제 요청 → 결제 순환 방지). 마감 시간은 POS 설정 키로 받는다.
 */
@Component
public class PaymentExpiry {

    private final PaymentTransactionRepository payments;
    private final TransactionTemplate transaction;
    private final Duration confirmWindow;
    private final Clock clock;

    public PaymentExpiry(PaymentTransactionRepository payments, TransactionTemplate transaction,
            @Value("${yamu.pos.confirm-window}") Duration confirmWindow, Clock clock) {
        this.payments = payments;
        this.transaction = transaction;
        this.confirmWindow = confirmWindow;
        this.clock = clock;
    }

    /** 아직 승인할 수 있는 상태(REQUESTED·DECLINED)인데 마감이 지났는지. */
    public boolean isDue(PaymentTransaction payment) {
        return payment.isConfirmable() && isPastDeadline(payment);
    }

    public boolean isPastDeadline(PaymentTransaction payment) {
        return payment.isPastDeadline(confirmWindow, clock.instant());
    }

    /**
     * 마감이 지난 REQUESTED·DECLINED를 EXPIRED로 저장한다.
     * 다른 요청이 먼저 상태를 바꿨으면(동시 승인·동시 만료) 다시 읽은 현재 상태를 돌려준다.
     *
     * @return 저장 뒤의 상태. EXPIRED면 부르는 쪽이 바코드도 EXPIRED로 바꾼다
     */
    public PaymentStatus expire(Long paymentId) {
        try {
            return transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (!isDue(payment)) {
                    return payment.getStatus();
                }
                payment.expire();
                payments.saveAndFlush(payment);
                return payment.getStatus();
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            return payments.findById(paymentId).map(PaymentTransaction::getStatus)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
    }
}
