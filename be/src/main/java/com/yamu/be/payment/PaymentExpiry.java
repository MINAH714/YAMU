package com.yamu.be.payment;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.pos.PosProperties;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 확정 마감(DEC-003: POS 요청 뒤 60초) 판정과 EXPIRED 전환.
 *
 * <p>임시(명세 「결제 상태·예외 처리」 8장 Q4 제안): 마감이 지난 REQUESTED·DECLINED는 승인 요청·바코드 조회가 그 자리에서 EXPIRED로
 * 바꾸고, 바코드도 EXPIRED로 둔다. 아무도 조회하지 않은 결제를 정리하는 주기 작업은 Task 5.2.6 이후에 둔다.
 * 출금이 걸려 있을 수 있는 PROCESSING은 마감이 지나도 바꾸지 않는다(복구는 Task 5.2.3).
 */
@Slf4j
@Component
public class PaymentExpiry {

    private final PaymentTransactionRepository payments;
    private final BarcodeStore barcodes;
    private final PosProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentExpiry(PaymentTransactionRepository payments, BarcodeStore barcodes, PosProperties properties,
            TransactionTemplate transaction, Clock clock) {
        this.payments = payments;
        this.barcodes = barcodes;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** 아직 승인할 수 있는 상태(REQUESTED·DECLINED)인데 마감이 지났는지. */
    public boolean isDue(PaymentTransaction payment) {
        return payment.isConfirmable() && isPastDeadline(payment);
    }

    public boolean isPastDeadline(PaymentTransaction payment) {
        return payment.isPastDeadline(properties.confirmWindow(), clock.instant());
    }

    /**
     * 마감이 지난 REQUESTED·DECLINED를 EXPIRED로 저장하고 바코드도 EXPIRED로 바꾼다.
     * 다른 요청이 먼저 상태를 바꿨으면(동시 승인·동시 만료) 다시 읽은 현재 상태를 돌려준다.
     *
     * @return 저장 뒤의 상태
     */
    public PaymentStatus expire(Long paymentId) {
        PaymentStatus after;
        try {
            after = transaction.execute(status -> {
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
            after = payments.findById(paymentId).map(PaymentTransaction::getStatus)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        if (after == PaymentStatus.EXPIRED) {
            try {
                barcodes.expireByPayment(paymentId);
            } catch (RuntimeException e) {
                log.warn("결제 만료 뒤 바코드 만료 반영 실패 paymentId={}", paymentId, e);
            }
        }
        return after;
    }
}
