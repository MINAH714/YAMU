package com.yamu.be.payment.reject;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.IdempotencyKeys;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import jakarta.persistence.OptimisticLockException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 앱의 결제 요청 거절(S15P31E103-360). 결정 근거는 Jira 이슈 「정한 규칙」.
 *
 * <pre>
 * ① Idempotency-Key 형식 검사(UUID)
 * ② 한 트랜잭션: 결제 행 잠금(소유 확인) → 상태별 판단
 *      REJECTED            → 그대로 200(다시 온 요청)
 *      REQUESTED·DECLINED  → 마감 지남이면 EXPIRED 저장 후 410, 출금 미확인 DECLINED면 409, 아니면 REJECTED
 *      PROCESSING          → 409 처리 중 / EXPIRED → 410 / APPROVED·CANCELED → 409 이미 처리됨
 * ③ 커밋 뒤 바코드를 EXPIRED로 닫는다. 실패해도 거절은 끝났으므로 로그만 남긴다
 * </pre>
 *
 * <p>멱등성은 상태로 지킨다. REJECTED는 다른 상태로 바뀌지 않으므로 같은 키든 다른 키든 다시 오면 같은 200이다. 그래서 취소처럼
 * 응답을 Redis에 기억하지 않는다. 다시 온 요청이 마감 뒤에 와도 410이 아니라 200이 되도록 REJECTED를 먼저 본다.
 *
 * <p>깨질 수 있는 곳: 같은 결제의 승인과 거절이 동시에 오면, 거절은 행 잠금을 잡고 승인은 version 비교로 선점하므로 먼저 커밋한 쪽만
 * 성공한다. 승인이 먼저 PROCESSING을 저장했으면 거절은 409 처리 중, 거절이 먼저면 승인은 409 이미 처리됨이다.
 * 출금 미확인 DECLINED는 복구 작업이 같은 요청 번호의 출금을 늦게 찾으면 APPROVED로 되돌리므로 거절로 닫지 않는다.
 */
@Slf4j
@Service
public class PaymentRejectService {

    private final PaymentTransactionRepository payments;
    private final PaymentExpiry expiry;
    private final BarcodeStore barcodes;
    private final TransactionTemplate transaction;

    public PaymentRejectService(PaymentTransactionRepository payments, PaymentExpiry expiry, BarcodeStore barcodes,
            TransactionTemplate transaction) {
        this.payments = payments;
        this.expiry = expiry;
        this.barcodes = barcodes;
        this.transaction = transaction;
    }

    public PaymentRejectResponse reject(Long userId, Long paymentId, PaymentRejectRequest request,
            String idempotencyKey) {
        IdempotencyKeys.require(idempotencyKey);
        Decision decision;
        try {
            decision = transaction.execute(status -> decide(userId, paymentId));
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            // 행 잠금을 잡고 바꾸므로 거의 없지만, 잠금 밖에서 바뀐 행이면 다시 읽어 그 상태로 답한다
            throw byStatus(payments.findById(paymentId).map(PaymentTransaction::getStatus)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        }
        if (decision.changed()) {
            closeBarcode(paymentId);
        }
        if (decision.status() == PaymentStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
        }
        log.info("결제 요청 거절 paymentId={} changed={}", paymentId, decision.changed());
        return new PaymentRejectResponse(paymentId, PaymentStatus.REJECTED);
    }

    /** 잠근 행으로 판단한다. 410(만료)은 EXPIRED를 저장해야 하므로 예외 대신 결과로 돌려준다(예외면 저장이 되돌아간다). */
    private Decision decide(Long userId, Long paymentId) {
        PaymentTransaction payment = payments.findForReject(paymentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.REJECTED) {
            return new Decision(PaymentStatus.REJECTED, false);
        }
        if (!payment.isConfirmable()) {
            throw byStatus(payment.getStatus());
        }
        if (expiry.isPastDeadline(payment)) {
            payment.expire();
            return new Decision(PaymentStatus.EXPIRED, true);
        }
        if (payment.isWithdrawalUnconfirmed()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REJECTABLE,
                    Map.of("reason", PaymentTransaction.WITHDRAWAL_NOT_CONFIRMED));
        }
        payment.reject();
        return new Decision(PaymentStatus.REJECTED, true);
    }

    /** 거절할 수 없는 상태를 응답 코드로. 승인 API와 같은 코드를 쓴다. */
    private static BusinessException byStatus(PaymentStatus status) {
        return switch (status) {
            case EXPIRED -> new BusinessException(ErrorCode.PAYMENT_EXPIRED);
            case APPROVED, CANCELED -> new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    Map.of("status", status.name()));
            default -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "결제를 처리하고 있습니다. 잠시 후 결과를 확인해 주세요.");
        };
    }

    /** 거절·만료한 결제의 바코드를 닫는다. 실패해도 결제는 끝났으므로 로그만 남긴다(바코드는 보관 시간이 지나면 사라진다). */
    private void closeBarcode(Long paymentId) {
        try {
            if (!barcodes.expireByPayment(paymentId)) {
                log.warn("결제 요청 거절·만료 뒤 바코드를 닫지 못함(보관 종료 또는 다른 상태) paymentId={}", paymentId);
            }
        } catch (RuntimeException e) {
            log.warn("결제 요청 거절·만료 뒤 바코드 닫기 실패 paymentId={}", paymentId, e);
        }
    }

    private record Decision(PaymentStatus status, boolean changed) {}
}
