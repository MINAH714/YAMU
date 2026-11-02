package com.yamu.be.payment.approve;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체크카드 승인 실행(Task 5.2.3, 명세 「결제 승인 요청」 처리 순서 4, 「[SSAFY] 계좌 출금」). 연결 계좌에서 출금액을 바로 뺀다.
 *
 * <pre>
 * ① 출금액(즉시할인 뒤 금액, 지금은 혜택 0원이라 요청액). 0원이면 출금 없이 승인(Q8)
 * ② 카드의 연결 계좌·userKey 복원. 없으면 거절(ACCOUNT_UNAVAILABLE, 임시)
 * ③ 기관거래고유번호 채번 → 결제 행에 먼저 저장(짧은 트랜잭션, 커밋이 출금보다 먼저)
 * ④ 출금(태그 YAMU-P{paymentId}) → 성공은 승인, 잔액 부족·계좌 없음·그 밖의 거절은 DECLINED,
 *    결과 불명은 금융망 예외(결제는 PROCESSING에 남아 WithdrawalRecovery가 확정), 못 보냄은 ApprovalNotSentException(결제를 되돌림)
 * </pre>
 *
 * <p>이 클래스는 결제 서비스가 결제별 정산 잠금을 잡은 채 DB 트랜잭션 밖에서 부른다. 자동으로 다시 출금하지 않는다.
 * 로그에는 결제 번호·태그·금액·결과만 남긴다(계좌번호·userKey 금지).
 */
@Slf4j
@Component
public class CheckCardApprover {

    private final PaymentAccountReader accounts;
    private final SsafyTransferApi transfer;
    private final PaymentTransactionRepository payments;
    private final ApprovalAttemptStore attempts;
    private final TransactionTemplate transaction;

    public CheckCardApprover(PaymentAccountReader accounts, SsafyTransferApi transfer,
            PaymentTransactionRepository payments, ApprovalAttemptStore attempts, TransactionTemplate transaction) {
        this.accounts = accounts;
        this.transfer = transfer;
        this.payments = payments;
        this.attempts = attempts;
        this.transaction = transaction;
    }

    public ApprovalOutcome approve(ApprovalCommand command) {
        Long paymentId = command.paymentId();
        long charged = chargedAmount(command);
        LocalDate performanceMonth = TemporaryCreditCardApprover.performanceMonth(command);
        if (charged <= 0) {
            log.info("체크카드 0원 승인, 출금 생략 paymentId={}", paymentId);
            return approved(command, 0, performanceMonth, null, null);
        }
        Optional<BankAccess> access = accounts.forCard(command.userId(), command.card().userCardId());
        if (access.isEmpty()) {
            log.warn("체크카드 출금 계좌 없음 paymentId={} card=****{}", paymentId, command.card().last4());
            return ApprovalOutcome.Declined.of(FailureCode.ACCOUNT_UNAVAILABLE);
        }

        String finRequestNo;
        try {
            finRequestNo = transfer.newTransactionNo();
        } catch (FinApiException e) {
            throw new ApprovalNotSentException("기관거래고유번호를 받지 못해 출금을 보내지 않았습니다.", e);
        }
        assignFinRequestNo(paymentId, finRequestNo);
        attachFinRequestNo(paymentId, command.attemptNo(), finRequestNo);

        TransferTag tag = TransferTag.payment(paymentId);
        TransferResult result = transfer.withdraw(access.get().userKey(), access.get().accountNo(), charged, tag,
                finRequestNo);
        return switch (result.status()) {
            case SUCCESS -> approved(command, charged, performanceMonth, finRequestNo, result.bankTransactionNo());
            case REJECTED -> declined(paymentId, result);
            case UNKNOWN -> {
                // 돈이 나갔을 수 있다. 결제는 PROCESSING에 두고 거래내역의 태그로 확정한다(WithdrawalRecovery)
                log.warn("체크카드 출금 결과 불명 paymentId={} tag={} code={}", paymentId, tag, result.finCode());
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
            }
            case NOT_SENT -> throw new ApprovalNotSentException("금융망에 연결하지 못해 출금을 보내지 않았습니다.", null);
        };
    }

    /** 출금액: 결제 금액 − 추천 후보의 즉시할인(PaymentTerms.chargedAmount). 후보가 아니면 즉시할인 0원이라 결제 금액. */
    static long chargedAmount(ApprovalCommand command) {
        long instantDiscount = command.candidate() != null ? command.candidate().instantDiscount() : 0;
        return command.terms().chargedAmount(instantDiscount);
    }

    /** 시도 기록에도 요청 번호를 남긴다. 행에는 이미 커밋됐으므로 Redis가 실패해도 출금은 진행한다. */
    private void attachFinRequestNo(Long paymentId, int attemptNo, String finRequestNo) {
        try {
            attempts.attachFinRequestNo(paymentId, attemptNo, finRequestNo);
        } catch (RuntimeException e) {
            log.warn("시도 기록에 출금 요청 번호를 남기지 못함(결제 행에는 있음) paymentId={} attempt={}", paymentId, attemptNo, e);
        }
    }

    /**
     * 출금 전에 기관거래고유번호를 결제 행에 남긴다(명세 1장 멱등성). 이 커밋이 출금 호출보다 먼저여야 결과를 모를 때 찾을 수 있다.
     * 행이 PROCESSING이 아니거나(복구·다른 요청이 바꿈) version이 어긋나면 출금하지 않는다.
     */
    private void assignFinRequestNo(Long paymentId, String finRequestNo) {
        try {
            transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (payment.getStatus() != PaymentStatus.PROCESSING) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
                }
                payment.assignFinRequestNo(finRequestNo);
                payments.saveAndFlush(payment);
                return null;
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            log.warn("출금 요청 번호 저장 중 결제 행이 바뀜, 출금하지 않음 paymentId={}", paymentId);
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
    }

    private static ApprovalOutcome approved(ApprovalCommand command, long charged, LocalDate performanceMonth,
            String finRequestNo, String bankTransactionNo) {
        // 임시: 혜택·실적 계산 전이라 승인액은 요청액, 실적 인정액은 0 (BE3·Task 5.2.5)
        return new ApprovalOutcome.Approved(command.terms().amount(), charged, 0, performanceMonth, List.of(),
                finRequestNo, bankTransactionNo);
    }

    private static ApprovalOutcome declined(Long paymentId, TransferResult result) {
        FailureCode code = switch (result.rejectReason()) {
            case INSUFFICIENT_BALANCE -> FailureCode.INSUFFICIENT_BALANCE;
            // 임시(Q9): 계좌 해지 등. linked_account 상태는 바꾸지 않는다(BE1과 합의 뒤)
            case ACCOUNT_NOT_FOUND -> FailureCode.ACCOUNT_UNAVAILABLE;
            case OTHER -> FailureCode.ISSUER_DECLINED;
        };
        log.info("체크카드 출금 거절 paymentId={} failure={} finCode={}", paymentId, code, result.finCode());
        return ApprovalOutcome.Declined.of(code);
    }
}
