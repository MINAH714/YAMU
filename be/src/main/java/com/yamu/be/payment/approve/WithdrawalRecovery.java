package com.yamu.be.payment.approve;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Attempt;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TagLookup;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과를 모르는 체크카드 출금(PROCESSING에 남은 결제)을 거래내역의 태그로 확정한다(명세 「결제 상태·예외 처리」 5장, Q5 임시).
 * 앱이 같은 결제를 다시 승인 요청할 때와 주기 작업({@link WithdrawalRecoveryJob})이 부른다.
 *
 * <ul>
 *   <li>요청 번호가 없음(선점 뒤 채번 전에 중단): 유예가 지났으면 REQUESTED로 되돌린다. 금융망에 간 적이 없다.</li>
 *   <li>태그 FOUND(금액 일치): 돈이 나갔다 → APPROVED(원장 반영·바코드 USED).</li>
 *   <li>태그 NOT_FOUND, 요청 번호 시각 + 유예 경과: 돈이 안 나갔다고 보고 DECLINED(WITHDRAWAL_NOT_CONFIRMED). 여기서는 다시 출금하지 않는다.</li>
 *   <li>금액 불일치·태그 둘 이상·조회 실패·신용카드 행: 자동 판정하지 않고 PROCESSING 유지, 로그로 운영 확인.</li>
 * </ul>
 *
 * <p>늦은 출금(임시, !69 리뷰): 은행이 유예 뒤에 첫 출금을 처리할 수 있다. 출금 미확인으로 거절·만료한 결제는
 * 재승인 직전({@link #recheckBeforeRetry})과 주기 작업의 대조 기간 동안({@link #recheckDeclined}) 같은 요청 번호의 태그를 다시 보고,
 * 찾으면 승인으로 바로잡는다(DECLINED·EXPIRED → APPROVED). 대조 기간이 지난 뒤의 늦은 처리는 운영이 태그로 대조한다(한계).
 *
 * <p>출금액 비교 기준은 출금 직전에 요청 번호와 함께 저장한 출금 요청액(fin_request_amount = 결제 금액 − 즉시할인)이다
 * (S15P31E103-292). 그 칸이 생기기 전에 출금한 행은 비어 있어 결제 요청액(requested_amount)으로 비교한다. 다르면 자동 판정하지 않는다.
 * 승인으로 확정할 때의 승인액도 이 금액이다.
 *
 * <p>결제별 정산 잠금으로 출금 실행 스레드·재요청·주기 작업이 서로 다른 결정을 내리지 못하게 한다.
 * 금융망 조회 실패는 판정을 미루고(PROCESSING), 그 밖의 예외(계좌 복호화·DB 저장·원장 반영)는 밖으로 낸다.
 */
@Slf4j
@Component
public class WithdrawalRecovery {

    static final String SETTLE_LOCK_PREFIX = "payment:approve:settle:";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentTransactionRepository payments;
    private final PaymentAccountReader accounts;
    private final SsafyTransferApi transfer;
    private final ApprovalAttemptStore attempts;
    private final ApprovalLedger ledger;
    private final BarcodeStore barcodes;
    private final PaymentExpiry expiry;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public WithdrawalRecovery(PaymentTransactionRepository payments, PaymentAccountReader accounts,
            SsafyTransferApi transfer, ApprovalAttemptStore attempts, ApprovalLedger ledger, BarcodeStore barcodes,
            PaymentExpiry expiry, RedisLocks locks, TransactionTemplate transaction,
            PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.accounts = accounts;
        this.transfer = transfer;
        this.attempts = attempts;
        this.ledger = ledger;
        this.barcodes = barcodes;
        this.expiry = expiry;
        this.locks = locks;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    /** 결제 하나의 출금 실행·결과 저장·복구가 겹치지 않게 잡는 잠금 키. 승인 서비스도 같은 키를 쓴다. */
    public static String settleLockKey(Long paymentId) {
        return SETTLE_LOCK_PREFIX + paymentId;
    }

    /**
     * 복구를 시도하고 그 뒤의 결제 상태를 돌려준다. 잠금을 못 잡거나 아직 판정할 수 없으면 PROCESSING이다.
     * 금융망 조회 실패는 PROCESSING으로 두고, 계좌 복호화·DB 저장·원장 반영 예외는 그대로 던진다.
     */
    public PaymentStatus recover(Long paymentId) {
        Optional<RedisLocks.Lock> lock = locks.tryLock(settleLockKey(paymentId), properties.settleLockTtl());
        if (lock.isEmpty()) {
            log.info("결제 복구 건너뜀(다른 처리가 잡고 있음) paymentId={}", paymentId);
            return PaymentStatus.PROCESSING;
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            PaymentTransaction payment = payments.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (payment.getStatus() != PaymentStatus.PROCESSING) {
                return payment.getStatus();
            }
            if (payment.getFinRequestNo() == null) {
                return recoverUnsent(payment);
            }
            return recoverByTag(payment);
        }
    }

    /**
     * 주기 작업의 늦은 출금 대조. 출금 미확인으로 거절·만료한 결제를 요청 번호 시각 + 대조 기간 안에서만 다시 본다.
     * 잠금을 못 잡으면 UNDECIDED(다음 주기에 다시)다.
     */
    public LateCheck recheckDeclined(Long paymentId) {
        Optional<RedisLocks.Lock> lock = locks.tryLock(settleLockKey(paymentId), properties.settleLockTtl());
        if (lock.isEmpty()) {
            return LateCheck.UNDECIDED;
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            PaymentTransaction payment = payments.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (!payment.isLateConfirmable(payment.getFinRequestNo())
                    || !clock.instant().isBefore(requestTime(payment.getFinRequestNo())
                            .plus(properties.lateWithdrawalWindow()))) {
                return LateCheck.NOT_APPLICABLE;
            }
            return lateCheck(payment);
        }
    }

    /**
     * 재승인 직전의 늦은 출금 확인. 승인 서비스가 정산 잠금을 잡은 채 부른다(같은 잠금을 다시 잡지 않는다).
     * 대조 기간과 상관없이, 출금 미확인으로 거절된 결제면 같은 요청 번호의 태그를 본다.
     */
    LateCheck recheckBeforeRetry(Long paymentId) {
        PaymentTransaction payment = payments.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!payment.isLateConfirmable(payment.getFinRequestNo())) {
            return LateCheck.NOT_APPLICABLE;
        }
        return lateCheck(payment);
    }

    /** 요청 번호가 없는 PROCESSING: 채번 전에 중단된 것. 유예가 지났으면 되돌린다. */
    private PaymentStatus recoverUnsent(PaymentTransaction payment) {
        if (!pastGrace(payment.getUpdatedAt().toInstant(ZoneOffset.UTC))) {
            return PaymentStatus.PROCESSING;
        }
        log.warn("출금 요청 번호 없는 PROCESSING 결제를 되돌림 paymentId={}", payment.getId());
        return release(payment.getId());
    }

    private PaymentStatus recoverByTag(PaymentTransaction payment) {
        Long paymentId = payment.getId();
        String finRequestNo = payment.getFinRequestNo();
        long amount = requestedWithdrawal(payment);
        Optional<TagLookup> lookup = lookup(payment);
        if (lookup.isEmpty()) {
            return PaymentStatus.PROCESSING;
        }
        return switch (lookup.get().result()) {
            case FOUND -> confirmApproved(paymentId, finRequestNo, amount, lookup.get().found());
            case NOT_FOUND -> {
                if (!pastGrace(requestTime(finRequestNo))) {
                    yield PaymentStatus.PROCESSING;
                }
                yield declineUnconfirmed(paymentId, finRequestNo);
            }
            default -> {
                logUndecided(paymentId, amount, lookup.get());
                yield PaymentStatus.PROCESSING;
            }
        };
    }

    /** 늦은 출금 확인: 찾으면 승인으로 바로잡는다. */
    private LateCheck lateCheck(PaymentTransaction payment) {
        Long paymentId = payment.getId();
        String finRequestNo = payment.getFinRequestNo();
        long amount = requestedWithdrawal(payment);
        Optional<TagLookup> lookup = lookup(payment);
        if (lookup.isEmpty()) {
            return LateCheck.UNDECIDED;
        }
        return switch (lookup.get().result()) {
            case FOUND -> confirmLate(paymentId, finRequestNo, amount, lookup.get().found()) == PaymentStatus.APPROVED
                    ? LateCheck.CONFIRMED : LateCheck.UNDECIDED;
            case NOT_FOUND -> LateCheck.NOT_FOUND;
            default -> {
                logUndecided(paymentId, amount, lookup.get());
                yield LateCheck.UNDECIDED;
            }
        };
    }

    /**
     * 요청 번호의 거래내역 조회. 계좌·키를 복원하지 못했거나, 신용카드 행이거나, 금융망 조회가 실패하면 비어 있다(판정 보류).
     * 계좌 복호화의 예외는 금융망 실패가 아니므로 그대로 던진다.
     */
    private Optional<TagLookup> lookup(PaymentTransaction payment) {
        Long paymentId = payment.getId();
        String finRequestNo = payment.getFinRequestNo();
        Optional<BankAccess> access = accounts.forCard(payment.getUserId(), payment.getUserCardId());
        if (access.isEmpty()) {
            log.error("복구할 결제의 출금 계좌·키를 복원하지 못함, 운영 확인 paymentId={}", paymentId);
            return Optional.empty();
        }
        if (access.get().cardType() != CardType.CHECK) {
            log.error("신용카드 결제에 출금 요청 번호가 있음, 운영 확인 paymentId={} finRequestNo={}", paymentId, finRequestNo);
            return Optional.empty();
        }
        try {
            return Optional.of(transfer.findByTag(access.get().userKey(), access.get().accountNo(),
                    TransferTag.payment(paymentId), requestedWithdrawal(payment), finRequestNo));
        } catch (BusinessException e) {
            // FinApiException(금융망 오류)과 응답을 읽지 못한 BusinessException. 그 밖의 예외(프로그래밍 오류)는 숨기지 않는다
            log.warn("출금 결과 확인용 거래내역 조회 실패, 다음에 다시 확인 paymentId={} code={}", paymentId, e.getErrorCode(), e);
            return Optional.empty();
        }
    }

    /**
     * 이 시도가 금융망에 보낸 출금액. 요청 번호와 함께 저장한 값이고, 그 칸이 생기기 전에 출금한 행(null)은 결제 요청액이다.
     * 옛 행은 즉시할인을 저장하지 않았으므로 할인이 있었다면 금액 불일치로 남아 운영이 확인한다.
     */
    static long requestedWithdrawal(PaymentTransaction payment) {
        return payment.getFinRequestAmount() != null ? payment.getFinRequestAmount() : payment.getRequestedAmount();
    }

    /** 자동 판정하지 않는 조회 결과. 금액이 다르면 거래 번호와 두 금액을 남긴다. */
    private static void logUndecided(Long paymentId, long expectedAmount, TagLookup lookup) {
        if (lookup.result() == TagLookup.Result.AMOUNT_MISMATCH) {
            for (BankTransaction match : lookup.matches()) {
                log.warn("출금 금액이 출금 요청액과 달라 자동 판정하지 않음, 운영 확인 paymentId={} bankTransactionNo={} "
                        + "expectedAmount={} bankAmount={}", paymentId, match.bankTransactionNo(), expectedAmount,
                        match.amount());
            }
            return;
        }
        log.warn("출금 결과를 자동으로 판정하지 않음, 운영 확인 paymentId={} tag={} result={} matches={}", paymentId,
                TransferTag.payment(paymentId), lookup.result(), lookup.matches().size());
    }

    /** 거래내역에 출금이 있다: 돈이 나갔으므로 승인으로 확정한다. */
    private PaymentStatus confirmApproved(Long paymentId, String finRequestNo, long amount, BankTransaction found) {
        ApprovalOutcome.Approved approved = approvedOutcome(finRequestNo, amount, found);
        LocalDateTime now = nowUtc();
        PaymentStatus after = settle(paymentId, finRequestNo, payment -> {
            payment.approve(approved.approvedAmount(), approved.recognizedPerformanceAmount(),
                    approved.performanceMonth(), now, approved.finTransactionNo());
            payments.saveAndFlush(payment);
            ledger.recordApproval(payment, approved);
            return PaymentStatus.APPROVED;
        });
        if (after == PaymentStatus.APPROVED) {
            afterApproved(paymentId, finRequestNo, amount, found);
            log.info("출금 결과 복구: 거래내역에서 찾아 승인 확정 paymentId={} finRequestNo={}", paymentId, finRequestNo);
        }
        return after;
    }

    /** 출금 미확인으로 거절·만료한 뒤 같은 요청 번호의 출금을 찾았다: 승인으로 바로잡는다. */
    private PaymentStatus confirmLate(Long paymentId, String finRequestNo, long amount, BankTransaction found) {
        ApprovalOutcome.Approved approved = approvedOutcome(finRequestNo, amount, found);
        LocalDateTime now = nowUtc();
        PaymentStatus after = change(paymentId, payment -> {
            if (!payment.isLateConfirmable(finRequestNo)) {
                return payment.getStatus();
            }
            payment.confirmLateWithdrawal(finRequestNo, approved.approvedAmount(),
                    approved.recognizedPerformanceAmount(), approved.performanceMonth(), now,
                    approved.finTransactionNo());
            payments.saveAndFlush(payment);
            ledger.recordApproval(payment, approved);
            return PaymentStatus.APPROVED;
        });
        if (after == PaymentStatus.APPROVED) {
            afterApproved(paymentId, finRequestNo, amount, found);
            log.warn("늦게 처리된 출금을 거래내역에서 찾아 승인으로 바로잡음 paymentId={} finRequestNo={} bankTransactionNo={}",
                    paymentId, finRequestNo, found.bankTransactionNo());
        }
        return after;
    }

    private void afterApproved(Long paymentId, String finRequestNo, long amount, BankTransaction found) {
        try {
            barcodes.markUsed(paymentId);
        } catch (RuntimeException e) {
            log.warn("복구 승인 뒤 바코드 USED 반영 실패 paymentId={}", paymentId, e);
        }
        closeAttempt(paymentId, finRequestNo,
                attempt -> attempt.withResult(Attempt.APPROVED, null, found.bankTransactionNo(), amount, null));
    }

    /** 유예가 지나도 거래내역에 없다: 돈이 안 나간 것으로 보고 거절(재승인 가능). 마감까지 지났으면 만료. */
    private PaymentStatus declineUnconfirmed(Long paymentId, String finRequestNo) {
        PaymentStatus after = settle(paymentId, finRequestNo, payment -> {
            payment.decline(FailureCode.WITHDRAWAL_NOT_CONFIRMED.name());
            payments.saveAndFlush(payment);
            return PaymentStatus.DECLINED;
        });
        if (after == PaymentStatus.DECLINED) {
            closeAttempt(paymentId, finRequestNo, attempt -> attempt.withResult(Attempt.DECLINED,
                    FailureCode.WITHDRAWAL_NOT_CONFIRMED.name(), null, null, null));
            log.warn("출금 결과 복구: 거래내역에 없어 거절 처리(재출금 없음) paymentId={} finRequestNo={}", paymentId, finRequestNo);
            after = expiry.expire(paymentId);
            if (after == PaymentStatus.EXPIRED) {
                expireBarcode(paymentId);
            }
        }
        return after;
    }

    /** PaymentExpiry는 바코드를 건드리지 않으므로 만료한 쪽이 바코드도 EXPIRED로 둔다. 실패는 로그만 남긴다. */
    private void expireBarcode(Long paymentId) {
        try {
            barcodes.expireByPayment(paymentId);
        } catch (RuntimeException e) {
            log.warn("복구 만료 뒤 바코드 만료 반영 실패 paymentId={}", paymentId, e);
        }
    }

    /** PROCESSING → REQUESTED. 금융망에 간 적이 없는 시도를 없던 일로 한다(시도 기록도 지워 횟수에 세지 않는다). */
    private PaymentStatus release(Long paymentId) {
        PaymentStatus after = settle(paymentId, null, payment -> {
            payment.releaseApproval();
            payments.saveAndFlush(payment);
            return PaymentStatus.REQUESTED;
        });
        if (after == PaymentStatus.REQUESTED) {
            try {
                attempts.lastProcessing(paymentId).ifPresent(attempt -> attempts.release(paymentId, attempt));
            } catch (RuntimeException e) {
                log.warn("되돌린 결제의 시도 기록을 지우지 못함(시도 횟수 하나를 씀) paymentId={}", paymentId, e);
            }
        }
        return after;
    }

    /**
     * 결제 행을 다시 읽어 아직 PROCESSING이고(요청 번호를 대조해) 같은 시도일 때만 바꾼다.
     * 그 사이 다른 처리가 바꿨으면 그 상태를 돌려준다.
     */
    private PaymentStatus settle(Long paymentId, String expectedFinRequestNo,
            Function<PaymentTransaction, PaymentStatus> change) {
        return change(paymentId, payment -> {
            if (payment.getStatus() != PaymentStatus.PROCESSING
                    || (expectedFinRequestNo != null && !expectedFinRequestNo.equals(payment.getFinRequestNo()))) {
                return payment.getStatus();
            }
            return change.apply(payment);
        });
    }

    private PaymentStatus change(Long paymentId, Function<PaymentTransaction, PaymentStatus> change) {
        try {
            return transaction.execute(status -> change.apply(payments.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))));
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            return payments.findById(paymentId).map(PaymentTransaction::getStatus).orElse(PaymentStatus.PROCESSING);
        }
    }

    /**
     * 그 요청 번호로 출금한 시도에 결과를 적는다. 요청 번호로 찾으므로 그 사이 새 시도가 생겨도 옛 시도를 닫는다.
     * 시도 기록은 응답 재생용이라 실패해도 판정은 그대로다.
     */
    private void closeAttempt(Long paymentId, String finRequestNo, UnaryOperator<Attempt> result) {
        try {
            attempts.findByFinRequestNo(paymentId, finRequestNo)
                    .ifPresent(attempt -> attempts.recordResult(paymentId, attempt, result.apply(attempt)));
        } catch (RuntimeException e) {
            log.warn("복구 결과를 시도 기록에 남기지 못함 paymentId={} finRequestNo={}", paymentId, finRequestNo, e);
        }
    }

    private ApprovalOutcome.Approved approvedOutcome(String finRequestNo, long amount, BankTransaction found) {
        LocalDate performanceMonth = nowUtc().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate()
                .withDayOfMonth(1);
        return new ApprovalOutcome.Approved(amount, amount, 0, performanceMonth, List.of(), finRequestNo,
                found.bankTransactionNo(), null);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private boolean pastGrace(Instant since) {
        return !clock.instant().isBefore(since.plus(properties.recoveryGrace()));
    }

    /**
     * 기관거래고유번호의 앞 14자리는 채번한 때의 한국 시각이다(스키마 사전 「금융망 요청 번호 채번 규칙」).
     * 신용카드 환급 입금(Task 5.4.4)도 유예 판단에 같이 쓴다.
     */
    public static Instant requestTime(String finRequestNo) {
        try {
            return LocalDateTime.parse(finRequestNo.substring(0, 14), REQUEST_TIME).atZone(KST).toInstant();
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("기관거래고유번호의 시각을 읽을 수 없습니다", e);
        }
    }

    /** 늦은 출금 확인 결과. */
    public enum LateCheck {
        /** 같은 요청 번호의 출금을 찾아 승인으로 바로잡음 */
        CONFIRMED,
        /** 거래내역에 없음. 재승인해도 된다 */
        NOT_FOUND,
        /** 조회 실패·금액 불일치·태그 둘 이상·잠금 경쟁. 지금은 판단하지 않는다 */
        UNDECIDED,
        /** 출금 미확인 거절·만료가 아니거나 대조 기간이 지남 */
        NOT_APPLICABLE
    }
}
