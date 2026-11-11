package com.yamu.be.payment.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.approve.ApprovalAttemptStore;
import com.yamu.be.payment.approve.ApprovalNumbers;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.approve.WithdrawalRecovery;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.pos.PosTerminalResolver;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * POS 결제 결과 조회(API 명세 「POS 결제 결과 조회」, FR-019·FR-020·FR-022). POS는 2초마다 불러 영수증을 띄울 때를 정한다.
 *
 * <pre>
 * ① POS 키 확인({@code PosKeyInterceptor}가 먼저 본다)
 * ② 결제를 찾고, 결제 요청을 만든 posCode(merchant_context_snapshot)가 요청의 posCode와 같은지 본다. 없거나 다르면 404
 * ③ 마감이 지난 REQUESTED·DECLINED는 그 자리에서 EXPIRED로 저장하고 바코드도 EXPIRED(임시: 명세 Q4 제안, 승인·바코드 조회와 같은 규칙)
 * ④ 응답: 승인번호는 계산(Q3), 즉시할인은 요청액 − 승인액, DECLINED에는 retryable(Q1),
 *    DECLINED·EXPIRED에는 늦은 출금 대조가 남았는지(lateConfirmPending)
 * </pre>
 *
 * <p>다른 POS: POS 키는 하나라 키로는 어느 POS인지 가릴 수 없다. 그래서 posCode를 받아 결제를 만든 POS와 대조한다.
 * 키와 posCode를 아는 쪽은 그 POS의 결제를 볼 수 있다(데모용 인증의 한계, {@code PosKeyInterceptor} 참고).
 *
 * <p>깨질 수 있는 곳: 조회의 만료 저장과 앱의 승인·다른 조회가 동시에 같은 행을 바꾸면 version이 어긋나 한 건만 저장되고,
 * {@link PaymentExpiry#expire}가 다시 읽은 상태를 돌려준다. 그래서 응답은 저장 뒤의 행을 다시 읽어 만든다.
 * 출금이 걸려 있을 수 있는 PROCESSING은 마감이 지나도 바꾸지 않으므로 POS는 계속 기다린다(복구는 Task 5.2.3).
 *
 * <p>한계: 즉시할인은 {@code approved_amount}가 할인 뒤 금액이라는 스키마 정의를 따른다. 승인 쪽(체크카드·신용카드·출금 복구)은
 * 결제 금액 − 즉시할인({@code ApprovalCommand#chargedAmount})을 승인액으로 저장한다.
 *
 * <p>늦은 출금: 출금 미확인으로 거절·만료된 결제는 주기 작업이 요청 번호 시각 + {@code late-withdrawal-window} 동안 다시 대조해
 * APPROVED로 바꿀 수 있다. 그동안 {@code lateConfirmPending=true}로 POS가 조회를 멈추지 않게 한다.
 * 기간 끝 직전에 시작한 대조는 정산 잠금을 잡은 채 은행 조회·저장을 하므로 저장이 기간 끝을 조금 넘길 수 있다.
 * 그래서 기간 끝 + {@code settle-lock-ttl}까지 true로 둔다(임시). 대조가 잠금 시간보다 오래 걸리거나,
 * 기간이 지난 뒤 운영이 거래내역 태그로 대조해 승인하면 POS는 알 수 없다.
 */
@Slf4j
@Service
public class PosPaymentResultService {

    private final PosTerminalResolver terminals;
    private final PaymentTransactionRepository payments;
    private final PaymentExpiry expiry;
    private final BarcodeStore barcodes;
    private final ApprovalAttemptStore attempts;
    private final PaymentCardLookup cards;
    private final PaymentApprovalProperties approveProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PosPaymentResultService(PosTerminalResolver terminals, PaymentTransactionRepository payments,
            PaymentExpiry expiry, BarcodeStore barcodes, ApprovalAttemptStore attempts, PaymentCardLookup cards,
            PaymentApprovalProperties approveProperties, ObjectMapper objectMapper, Clock clock) {
        this.terminals = terminals;
        this.payments = payments;
        this.expiry = expiry;
        this.barcodes = barcodes;
        this.attempts = attempts;
        this.cards = cards;
        this.approveProperties = approveProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PosPaymentResult get(String posKey, String posCode, Long paymentId) {
        terminals.authenticate(posKey);
        Found found = find(paymentId, posCode);
        PaymentTransaction payment = found.payment();
        if (expiry.isDue(payment)) {
            expire(paymentId);
            payment = payments.findById(paymentId).orElseThrow(PosPaymentResultService::notFound);
        }
        return result(payment, found.context().orderNo());
    }

    /** 이 POS가 만든 결제. 없는 결제, 다른 posCode의 결제, POS 요청이 아닌 거래(스냅샷 없음)는 모두 404다. */
    private Found find(Long paymentId, String posCode) {
        return payments.findById(paymentId)
                .flatMap(payment -> MerchantContextSnapshot.of(payment, objectMapper)
                        .filter(context -> context.posCode() != null && context.posCode().equals(posCode))
                        .map(context -> new Found(payment, context)))
                .orElseThrow(PosPaymentResultService::notFound);
    }

    /** 마감이 지난 결제를 EXPIRED로 저장하고 바코드도 EXPIRED로 둔다. 바코드 반영 실패는 로그만 남긴다. */
    private void expire(Long paymentId) {
        if (expiry.expire(paymentId) != PaymentStatus.EXPIRED) {
            return;
        }
        try {
            barcodes.expireByPayment(paymentId);
        } catch (RuntimeException e) {
            log.warn("결제 만료 뒤 바코드 만료 반영 실패 paymentId={}", paymentId, e);
        }
    }

    private PosPaymentResult result(PaymentTransaction payment, String orderNo) {
        PaymentStatus status = payment.getStatus();
        LocalDateTime approvedAt = payment.getApprovedAt();
        String approvalNo = approvedAt == null ? null : ApprovalNumbers.of(approvedAt, payment.getId());
        Long charged = approvedAt == null ? null : payment.getApprovedAmount();
        Long instantDiscount = charged == null ? null : payment.getRequestedAmount() - charged;
        return new PosPaymentResult(payment.getId(), orderNo, status, approvalNo, approvedAt,
                payment.getRequestedAmount(), charged, instantDiscount, card(payment), failure(payment.getFailureCode()),
                status == PaymentStatus.DECLINED ? retryable(payment) : null, lateConfirmPending(payment));
    }

    /**
     * 승인·거절로 정해진 카드. 승인 전(REQUESTED)·처리 중(PROCESSING)의 카드는 아직 결과가 아니라 보이지 않는다.
     * 승인 되돌리기({@code releaseApproval}) 뒤의 REQUESTED는 행에 카드가 남아 있어도 숨긴다.
     */
    private PosPaymentResult.Card card(PaymentTransaction payment) {
        PaymentStatus status = payment.getStatus();
        if (payment.getUserCardId() == null || status == PaymentStatus.REQUESTED
                || status == PaymentStatus.PROCESSING) {
            return null;
        }
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;
        return cards.findReceiptCard(payment.getUserCardId())
                .map(card -> new PosPaymentResult.Card(card.issuerCode(), card.cardType(),
                        "****-****-****-" + card.last4(), plan, months))
                .orElse(null);
    }

    private static PosPaymentResult.Failure failure(String code) {
        if (code == null) {
            return null;
        }
        return new PosPaymentResult.Failure(code, PosFailureMessages.of(code));
    }

    /**
     * 거절된 결제를 다른 카드로 다시 승인할 수 있는지. 마감 전이고 시도 횟수가 남았어야 한다.
     * 시도 기록(Redis)을 읽지 못하면 true로 둔다. POS는 계속 기다리고, 마감이 지나면 EXPIRED로 끝난다.
     */
    private boolean retryable(PaymentTransaction payment) {
        if (expiry.isPastDeadline(payment)) {
            return false;
        }
        try {
            return attempts.count(payment.getId()) < approveProperties.maxAttempts();
        } catch (RuntimeException e) {
            log.warn("승인 시도 횟수를 읽지 못해 재승인 가능으로 응답 paymentId={}", payment.getId(), e);
            return true;
        }
    }

    /**
     * 늦은 출금 대조가 남았는지. 주기 작업({@link WithdrawalRecovery#recheckDeclined})과 같은 조건(출금 미확인 거절·만료,
     * 요청 번호 시각 + 대조 기간 전)에 정산 잠금 시간만큼 여유를 더한다. DECLINED·EXPIRED가 아니면 null.
     */
    private Boolean lateConfirmPending(PaymentTransaction payment) {
        PaymentStatus status = payment.getStatus();
        if (status != PaymentStatus.DECLINED && status != PaymentStatus.EXPIRED) {
            return null;
        }
        String finRequestNo = payment.getFinRequestNo();
        if (!payment.isLateConfirmable(finRequestNo)) {
            return false;
        }
        Instant until = WithdrawalRecovery.lateCheckEnd(finRequestNo, approveProperties.lateWithdrawalWindow())
                .plus(approveProperties.settleLockTtl());
        return clock.instant().isBefore(until);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private record Found(PaymentTransaction payment, MerchantContextSnapshot context) {}
}
