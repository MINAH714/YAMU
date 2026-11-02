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
 *   <li>태그 NOT_FOUND, 요청 번호 시각 + 유예 경과: 돈이 안 나갔다고 보고 DECLINED(WITHDRAWAL_NOT_CONFIRMED). 재출금은 하지 않는다(Jira 199).
 *       금융망이 그 뒤에 처리하면 돈만 나간 채 DECLINED가 될 수 있다 → 운영이 태그로 대조(한계).</li>
 *   <li>금액 불일치·태그 둘 이상·조회 실패·신용카드 행: 자동 판정하지 않고 PROCESSING 유지, 로그로 운영 확인.</li>
 * </ul>
 *
 * 결제별 정산 잠금으로 출금 실행 스레드·재요청·주기 작업이 서로 다른 결정을 내리지 못하게 한다.
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
     * 조회·저장이 실패해도 예외를 밖으로 내지 않는다(호출한 쪽은 상태만 보고 응답한다).
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
        Optional<BankAccess> access = accounts.forCard(payment.getUserId(), payment.getUserCardId());
        if (access.isEmpty()) {
            log.error("복구할 결제의 출금 계좌·키를 복원하지 못함, 운영 확인 paymentId={}", paymentId);
            return PaymentStatus.PROCESSING;
        }
        if (access.get().cardType() != CardType.CHECK) {
            log.error("신용카드 결제에 출금 요청 번호가 있음, 운영 확인 paymentId={} finRequestNo={}", paymentId, finRequestNo);
            return PaymentStatus.PROCESSING;
        }
        TransferTag tag = TransferTag.payment(paymentId);
        long amount = payment.getRequestedAmount(); // 임시: 혜택 0원이라 출금액 = 요청액. 즉시할인이 생기면 시도 기록의 출금액을 쓴다
        TagLookup lookup;
        try {
            lookup = transfer.findByTag(access.get().userKey(), access.get().accountNo(), tag, amount, finRequestNo);
        } catch (RuntimeException e) {
            log.warn("출금 결과 확인용 거래내역 조회 실패, 다음에 다시 확인 paymentId={}", paymentId, e);
            return PaymentStatus.PROCESSING;
        }
        return switch (lookup.result()) {
            case FOUND -> confirmApproved(paymentId, finRequestNo, amount, lookup.found());
            case NOT_FOUND -> {
                if (!pastGrace(requestTime(finRequestNo))) {
                    yield PaymentStatus.PROCESSING;
                }
                yield declineUnconfirmed(paymentId, finRequestNo);
            }
            default -> {
                log.warn("출금 결과를 자동으로 판정하지 않음, 운영 확인 paymentId={} tag={} result={}", paymentId, tag,
                        lookup.result());
                yield PaymentStatus.PROCESSING;
            }
        };
    }

    /** 거래내역에 출금이 있다: 돈이 나갔으므로 승인으로 확정한다. */
    private PaymentStatus confirmApproved(Long paymentId, String finRequestNo, long amount, BankTransaction found) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        LocalDate performanceMonth = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate().withDayOfMonth(1);
        ApprovalOutcome.Approved approved = new ApprovalOutcome.Approved(amount, amount, 0, performanceMonth,
                List.of(), finRequestNo, found.bankTransactionNo(), null);
        PaymentStatus after = settle(paymentId, finRequestNo, payment -> {
            payment.approve(approved.approvedAmount(), approved.recognizedPerformanceAmount(),
                    approved.performanceMonth(), now, approved.finTransactionNo());
            payments.saveAndFlush(payment);
            ledger.recordApproval(payment, approved);
            return PaymentStatus.APPROVED;
        });
        if (after == PaymentStatus.APPROVED) {
            try {
                barcodes.markUsed(paymentId);
            } catch (RuntimeException e) {
                log.warn("복구 승인 뒤 바코드 USED 반영 실패 paymentId={}", paymentId, e);
            }
            closeAttempt(paymentId, ApprovalAttemptStore.APPROVED, null);
            log.info("출금 결과 복구: 거래내역에서 찾아 승인 확정 paymentId={} finRequestNo={}", paymentId, finRequestNo);
        }
        return after;
    }

    /** 유예가 지나도 거래내역에 없다: 돈이 안 나간 것으로 보고 거절(재승인 가능). 마감까지 지났으면 만료. */
    private PaymentStatus declineUnconfirmed(Long paymentId, String finRequestNo) {
        PaymentStatus after = settle(paymentId, finRequestNo, payment -> {
            payment.decline(FailureCode.WITHDRAWAL_NOT_CONFIRMED.name());
            payments.saveAndFlush(payment);
            return PaymentStatus.DECLINED;
        });
        if (after == PaymentStatus.DECLINED) {
            closeAttempt(paymentId, ApprovalAttemptStore.DECLINED, FailureCode.WITHDRAWAL_NOT_CONFIRMED.name());
            log.warn("출금 결과 복구: 거래내역에 없어 거절 처리(재출금 없음) paymentId={} finRequestNo={}", paymentId, finRequestNo);
            after = expiry.expire(paymentId);
        }
        return after;
    }

    /** PROCESSING → REQUESTED. 금융망에 간 적이 없는 시도를 없던 일로 한다. */
    private PaymentStatus release(Long paymentId) {
        PaymentStatus after = settle(paymentId, null, payment -> {
            payment.releaseApproval();
            payments.saveAndFlush(payment);
            return PaymentStatus.REQUESTED;
        });
        if (after == PaymentStatus.REQUESTED) {
            closeAttempt(paymentId, ApprovalAttemptStore.NOT_SENT, null);
        }
        return after;
    }

    /**
     * 결제 행을 다시 읽어 아직 PROCESSING이고(요청 번호를 대조해) 같은 시도일 때만 바꾼다.
     * 그 사이 다른 처리가 바꿨으면 그 상태를 돌려준다.
     */
    private PaymentStatus settle(Long paymentId, String expectedFinRequestNo,
            Function<PaymentTransaction, PaymentStatus> change) {
        try {
            return transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (payment.getStatus() != PaymentStatus.PROCESSING
                        || (expectedFinRequestNo != null && !expectedFinRequestNo.equals(payment.getFinRequestNo()))) {
                    return payment.getStatus();
                }
                return change.apply(payment);
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            return payments.findById(paymentId).map(PaymentTransaction::getStatus).orElse(PaymentStatus.PROCESSING);
        }
    }

    private void closeAttempt(Long paymentId, String outcome, String failureCode) {
        Optional<Attempt> last = attempts.lastProcessing(paymentId);
        last.ifPresent(attempt -> attempts.finish(attempt, outcome, failureCode, paymentId));
    }

    private boolean pastGrace(Instant since) {
        return !clock.instant().isBefore(since.plus(properties.recoveryGrace()));
    }

    /** 기관거래고유번호의 앞 14자리는 채번한 때의 한국 시각이다(스키마 사전 「금융망 요청 번호 채번 규칙」). */
    static Instant requestTime(String finRequestNo) {
        try {
            return LocalDateTime.parse(finRequestNo.substring(0, 14), REQUEST_TIME).atZone(KST).toInstant();
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("기관거래고유번호의 시각을 읽을 수 없습니다", e);
        }
    }
}
