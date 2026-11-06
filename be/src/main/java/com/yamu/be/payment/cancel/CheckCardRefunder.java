package com.yamu.be.payment.cancel;

import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.PaymentAccountReader;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 체크카드 취소 환불 입금(Task 5.3.3, POL-006, 노션 정책 초안 v2 §4-4). 취소 서비스가 결제별 정산 잠금을 잡은 채 DB 트랜잭션 밖에서 부른다.
 * 여기서 돈이 돌아간 것이 확인돼야 결제를 CANCELED로 바꾼다. 예외를 던지면 결제는 APPROVED로 남고 사용자가 다시 취소한다.
 *
 * <pre>
 * ① 출금 요청 번호가 없으면(0원 승인) 입금 없이 끝
 * ② 입금 계좌·userKey: 취소 시점의 카드 연결 계좌(임시). 없으면 409 ACCOUNT_UNAVAILABLE(임시)
 * ③ 환불액 = 원 출금(YAMU-P{paymentId})의 실제 출금액. 결제 행에는 즉시할인 뒤 출금액이 없어 거래내역에서 읽는다
 * ④ 이미 입금했는지: 승인일(한국) 전날 ~ 오늘의 입금 내역에서 YAMU-C{paymentId}를 찾는다. 있으면 다시 입금하지 않는다
 * ⑤ 없고, 직전 입금이 유예(recoveryGrace) 안이면 409 처리 중(SSAFY가 아직 처리 중일 수 있다)
 * ⑥ 입금 전에 보낸 시각을 남기고 입금한다. 성공이면 거래내역의 거래 후 잔액을 응답 잔액으로 쓴다(못 읽으면 null)
 * </pre>
 *
 * <p>자동으로 판단하지 않는 경우(원 출금이 0건·2건 이상, 출금액이 승인액보다 큼, 환불 태그가 2건 이상이거나 금액이 다름)는
 * 409 PAYMENT_NOT_CANCELABLE reason REFUND_NEEDS_REVIEW(임시)로 막고 운영 로그를 남긴다.
 *
 * <p>한계: 입금 결과를 모른 채 사용자가 다시 취소하지 않으면 결제는 APPROVED로 남는다(주기 복구는 별도 Task).
 * 로그에는 결제 번호·태그·금액·결과만 남긴다(계좌번호·userKey 금지).
 */
@Slf4j
@Component
public class CheckCardRefunder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentAccountReader accounts;
    private final SsafyTransferApi transfer;
    private final CheckRefundAttemptStore attempts;
    private final AccountBalanceService balances;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public CheckCardRefunder(PaymentAccountReader accounts, SsafyTransferApi transfer, CheckRefundAttemptStore attempts,
            AccountBalanceService balances, PaymentApprovalProperties properties, Clock clock) {
        this.accounts = accounts;
        this.transfer = transfer;
        this.attempts = attempts;
        this.balances = balances;
        this.properties = properties;
        this.clock = clock;
    }

    /** 승인된 체크카드 결제의 환불 입금을 끝낸다(이미 입금됐으면 확인만). 끝내지 못하면 예외. */
    public Refunded refund(PaymentTransaction payment) {
        Long paymentId = payment.getId();
        if (payment.getFinRequestNo() == null) {
            log.info("체크카드 출금 없는 승인(0원)의 취소, 입금 생략 paymentId={}", paymentId);
            return Refunded.NONE;
        }
        BankAccess access = accounts.forCard(payment.getUserId(), payment.getUserCardId()).orElseThrow(() -> {
            log.warn("체크카드 취소 환불 계좌 없음(카드 해제·계좌 끊김) paymentId={}", paymentId);
            return PaymentCancelService.notCancelable("ACCOUNT_UNAVAILABLE", Map.of());
        });
        if (access.cardType() != CardType.CHECK) {
            throw new IllegalStateException("체크카드 환불을 체크카드가 아닌 결제에 불렀습니다 (결제 " + paymentId + ")");
        }
        long withdrawn = withdrawnAmount(payment, access);

        TransferTag tag = TransferTag.checkRefund(paymentId);
        List<BankTransaction> deposits = transfer.findAllByTag(access.userKey(), access.accountNo(), tag,
                approvedOn(payment).minusDays(1), today());
        if (deposits.size() > 1) {
            throw needsReview(paymentId, "환불 입금이 " + deposits.size() + "건");
        }
        if (deposits.size() == 1) {
            BankTransaction found = deposits.get(0);
            if (found.amount() != withdrawn) {
                throw needsReview(paymentId, "환불 입금액 " + found.amount() + "원이 출금액 " + withdrawn + "원과 다름");
            }
            log.info("체크카드 환불 입금을 거래내역에서 찾음, 다시 입금하지 않음 paymentId={} tag={} bankTransactionNo={}",
                    paymentId, tag, found.bankTransactionNo());
            balances.evict(access.linkedAccountId());
            return new Refunded(withdrawn, found.balanceAfter());
        }
        Optional<Instant> sentAt = attempts.lastSentAt(paymentId);
        if (sentAt.isPresent() && clock.instant().isBefore(sentAt.get().plus(properties.recoveryGrace()))) {
            log.info("체크카드 환불 입금 결과 확인 대기 중이라 다시 입금하지 않음 paymentId={} sentAt={}", paymentId, sentAt.get());
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "환불 입금 결과를 확인하고 있습니다. 잠시 후 다시 취소해 주세요.");
        }
        return deposit(paymentId, access, tag, withdrawn);
    }

    /**
     * 원 출금의 실제 출금액. 승인 때 저장한 요청 번호의 조회 기간에서 YAMU-P 태그를 찾는다.
     * 조회 실패는 금융망 예외 그대로(다시 시도), 한 건이 아니거나 승인액보다 크면 운영 확인.
     */
    private long withdrawnAmount(PaymentTransaction payment, BankAccess access) {
        Long paymentId = payment.getId();
        List<BankTransaction> withdrawals = transfer.findAllByTag(access.userKey(), access.accountNo(),
                TransferTag.payment(paymentId), payment.getFinRequestNo());
        if (withdrawals.size() != 1) {
            throw needsReview(paymentId, "원 출금이 " + withdrawals.size() + "건");
        }
        long withdrawn = withdrawals.get(0).amount();
        Long approvedAmount = payment.getApprovedAmount();
        if (withdrawn <= 0 || approvedAmount == null || withdrawn > approvedAmount) {
            throw needsReview(paymentId, "원 출금액 " + withdrawn + "원, 승인액 " + approvedAmount + "원");
        }
        return withdrawn;
    }

    private Refunded deposit(Long paymentId, BankAccess access, TransferTag tag, long amount) {
        // 채번 실패는 금융망 예외(보내지 않음) 그대로. 시각을 남기지 못해도 입금하지 않는다
        String transactionNo = transfer.newTransactionNo();
        attempts.recordSending(paymentId, clock.instant());
        TransferResult result = transfer.deposit(access.userKey(), access.accountNo(), amount, tag, transactionNo);
        return switch (result.status()) {
            case SUCCESS -> {
                log.info("체크카드 환불 입금 성공 paymentId={} tag={} amount={}", paymentId, tag, amount);
                balances.evict(access.linkedAccountId());
                yield new Refunded(amount, balanceAfter(paymentId, access, tag, amount, transactionNo));
            }
            case REJECTED -> {
                clearQuietly(paymentId);
                log.warn("체크카드 환불 입금 거절 paymentId={} reason={} code={}", paymentId, result.rejectReason(),
                        result.finCode());
                if (result.rejectReason() == TransferResult.RejectReason.ACCOUNT_NOT_FOUND) {
                    throw PaymentCancelService.notCancelable("ACCOUNT_UNAVAILABLE", Map.of());
                }
                throw new BusinessException(ErrorCode.FIN_API_ERROR);
            }
            case UNKNOWN -> {
                // 돈이 들어갔을 수 있다. 보낸 시각을 남겨 두고, 다음 취소가 거래내역의 태그로 확정한다
                log.warn("체크카드 환불 입금 결과 불명 paymentId={} tag={} code={}", paymentId, tag, result.finCode());
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE,
                        "환불 입금 결과를 확인하지 못했습니다. 잠시 후 다시 취소해 주세요.");
            }
            case NOT_SENT -> {
                clearQuietly(paymentId);
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
            }
        };
    }

    /** 방금 입금한 거래의 거래 후 잔액. 아직 내역에 없거나 조회가 실패하면 null(취소는 그대로 진행). */
    private Long balanceAfter(Long paymentId, BankAccess access, TransferTag tag, long amount, String transactionNo) {
        try {
            List<BankTransaction> found = transfer.findAllByTag(access.userKey(), access.accountNo(), tag,
                    transactionNo);
            if (found.size() == 1 && found.get(0).amount() == amount) {
                return found.get(0).balanceAfter();
            }
            log.info("환불 입금 뒤 잔액을 거래내역에서 찾지 못함(응답 잔액 null) paymentId={} matches={}", paymentId,
                    found.size());
        } catch (BusinessException e) {
            log.info("환불 입금 뒤 잔액 조회 실패(응답 잔액 null) paymentId={} code={}", paymentId, e.getErrorCode());
        }
        return null;
    }

    /** 돈이 움직이지 않은 것이 확실할 때만 부른다. 지우지 못하면 유예 동안 409 처리 중이 날 뿐이다. */
    private void clearQuietly(Long paymentId) {
        try {
            attempts.clear(paymentId);
        } catch (RuntimeException e) {
            log.warn("체크카드 환불 시도 기록을 지우지 못함(유예 동안 재시도는 409) paymentId={}", paymentId, e);
        }
    }

    private static BusinessException needsReview(Long paymentId, String what) {
        log.error("체크카드 취소 환불을 자동으로 판단하지 않음, 운영 확인 paymentId={} {}", paymentId, what);
        return PaymentCancelService.notCancelable("REFUND_NEEDS_REVIEW", Map.of());
    }

    private static LocalDate approvedOn(PaymentTransaction payment) {
        return payment.getApprovedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    /**
     * @param amount       환불한 금액(원). 0원 승인은 0
     * @param balanceAfter 입금 뒤 잔액. 입금이 없었거나 읽지 못하면 null
     */
    public record Refunded(long amount, Long balanceAfter) {

        static final Refunded NONE = new Refunded(0, null);
    }
}
