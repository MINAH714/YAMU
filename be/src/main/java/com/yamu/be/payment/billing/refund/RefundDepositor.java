package com.yamu.be.payment.billing.refund;

import com.yamu.be.payment.approve.PaymentAccountReader;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.approve.WithdrawalRecovery;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신용카드 취소 환급 한 건을 입금한다(Task 5.4.4, POL-006). 기한이 정해진 대기 환급만 다룬다. 기한이 없는 대기 환급은 원 청구서의
 * 출금 결과를 기다리는 중이라 금액이 확정되지 않았다.
 *
 * <pre>
 * ① 직전 입금 요청이 유예(approve.recovery-grace) 안이면 건너뛴다. SSAFY가 아직 처리 중일 수 있다
 * ② 환급 계좌: 환급 행에 저장한 계좌(청구서 확정 당시 출금 계좌). 앱에서 연결을 끊었어도 그 계좌로 보낸다
 * ③ 이미 입금했는지: 환급 생성일(한국) 전날 ~ 오늘의 입금 내역에서 YAMU-R{refundId}를 찾는다. 있으면 완료로 바꾸고 다시 보내지 않는다
 *    (입금 뒤 DB 저장 실패, 결과 불명 뒤 늦은 처리를 같이 막는다)
 * ④ 없으면 새 요청 번호를 환급 행에 먼저 커밋하고 입금한다
 * ⑤ 성공 → COMPLETED + 회차 REFUNDED + 이벤트 / 계좌 없음(A1003) → FAILED + 이벤트 / 그 밖의 거절 → 대기 그대로 + 운영 로그
 *    / 결과 불명 → 그대로(다음 실행이 ①③으로 확정) / 요청 안 감 → 남긴 번호를 지운다
 * </pre>
 *
 * <p>거절 중 FAILED로 끝내는 것은 계좌 없음(A1003)뿐이다. 같은 계좌로 다시 보내도 소용없기 때문이다. API 키·헤더·요청 형식처럼
 * 우리 쪽 원인이거나 뜻을 모르는 거절 코드는 원인을 고치면 들어갈 수 있으므로 대기로 두고 남긴 번호도 그대로 둔다. 다음 실행은 유예가
 * 지난 뒤 ③의 태그 확인부터 하므로, 거절이 실제로는 처리된 경우에도 두 번 보내지 않는다.
 *
 * <p>같은 환급을 두 실행이 동시에 다루면 ④에서 환급 행을 잠그고 버전을 비교해 한쪽만 보낸다. 늦은 쪽은 남긴 번호를 보고 ①에서 멈춘다.
 * 자동으로 판단하지 않는 경우(태그가 2건 이상이거나 금액이 다름, 환급 계좌·회원 키를 읽을 수 없음)는 대기로 두고 운영 로그를 남긴다.
 *
 * <p>한계: 입금 응답이 유예보다 늦게 오는 사이 다른 실행이 ③을 지나 다시 보내면 두 번 들어갈 수 있다(응답 제한 10초, 유예 30초).
 * 로그에는 환급 번호·태그·금액·결과만 남긴다(계좌번호·userKey 금지).
 */
@Slf4j
@Component
public class RefundDepositor {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RefundRepository refunds;
    private final BillItemRepository items;
    private final PaymentAccountReader accounts;
    private final SsafyTransferApi transfer;
    private final PaymentApprovalProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RefundDepositor(RefundRepository refunds, BillItemRepository items, PaymentAccountReader accounts,
            SsafyTransferApi transfer, PaymentApprovalProperties properties, ApplicationEventPublisher events,
            TransactionTemplate transaction, Clock clock) {
        this.refunds = refunds;
        this.items = items;
        this.accounts = accounts;
        this.transfer = transfer;
        this.properties = properties;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * 환급 한 건을 입금하거나 입금을 확인한다. 금융망 조회 실패·채번 실패는 예외 그대로다(아무것도 바꾸지 않았으니 다음 실행이 다시 본다).
     */
    public Result deposit(Long refundId) {
        Snapshot refund = transaction.execute(status -> refunds.findById(refundId).map(Snapshot::of).orElse(null));
        if (refund == null || !refund.target()) {
            return Result.of(Outcome.SKIPPED);
        }
        if (refund.finRequestNo() != null && !pastGrace(refund.finRequestNo())) {
            log.info("환급 입금 결과 확인 대기 중이라 건너뜀 refundId={}", refundId);
            return Result.of(Outcome.IN_FLIGHT);
        }
        if (today().isAfter(refund.dueDate())) {
            log.warn("환급 기한을 넘김, 계속 입금 시도 refundId={} due={}", refundId, refund.dueDate());
        }
        BankAccess access = accounts.forRefundAccount(refund.userId(), refund.linkedAccountId()).orElse(null);
        if (access == null) {
            log.error("환급 계좌나 SSAFY 회원 키를 읽을 수 없음, 운영 확인 refundId={} linkedAccountId={}", refundId,
                    refund.linkedAccountId());
            return Result.of(Outcome.NEEDS_REVIEW);
        }

        TransferTag tag = TransferTag.refund(refundId);
        List<BankTransaction> found = transfer.findAllByTag(access.userKey(), access.accountNo(), tag,
                refund.createdOn().minusDays(1), today());
        if (found.size() > 1) {
            return Result.of(needsReview(refundId, "환급 입금이 " + found.size() + "건"));
        }
        if (found.size() == 1) {
            BankTransaction deposited = found.get(0);
            if (deposited.amount() != refund.amount()) {
                return Result.of(needsReview(refundId,
                        "환급 입금액 " + deposited.amount() + "원이 환급액 " + refund.amount() + "원과 다름"));
            }
            log.info("환급 입금을 거래내역에서 찾음, 다시 보내지 않음 refundId={} tag={}", refundId, tag);
            return Result.of(complete(refundId, deposited.bankTransactionNo()));
        }

        // 채번 실패는 금융망 예외(보내지 않음) 그대로
        String requestNo = transfer.newTransactionNo();
        if (!Boolean.TRUE.equals(transaction.execute(status -> claim(refund, requestNo)))) {
            log.info("다른 실행이 먼저 환급을 바꿈, 보내지 않음 refundId={}", refundId);
            return Result.of(Outcome.CONTENDED);
        }
        TransferResult result = transfer.deposit(access.userKey(), access.accountNo(), refund.amount(), tag, requestNo);
        return switch (result.status()) {
            case SUCCESS -> {
                log.info("환급 입금 성공 refundId={} tag={} amount={}", refundId, tag, refund.amount());
                yield Result.of(complete(refundId, result.bankTransactionNo()));
            }
            case REJECTED -> {
                if (result.rejectReason() == TransferResult.RejectReason.ACCOUNT_NOT_FOUND) {
                    log.warn("환급 계좌가 없어 입금 거절, 실패로 끝냄 refundId={} code={}", refundId, result.finCode());
                    yield Result.of(fail(refundId, requestNo, result.rejectReason()));
                }
                // 우리 쪽 원인(API 키·헤더·요청 형식)이거나 뜻을 모르는 코드. 원인을 고치면 들어갈 수 있으니 대기로 둔다
                log.error("환급 입금 거절, 대기로 두고 운영 확인 refundId={} reason={} code={}", refundId,
                        result.rejectReason(), result.finCode());
                yield new Result(Outcome.REJECTED, result.finCode());
            }
            case UNKNOWN -> {
                // 돈이 들어갔을 수 있다. 남긴 번호로 유예를 지킨 뒤 다음 실행이 거래내역의 태그로 확정한다
                log.warn("환급 입금 결과 불명 refundId={} tag={} code={}", refundId, tag, result.finCode());
                yield Result.of(Outcome.UNKNOWN);
            }
            case NOT_SENT -> {
                transaction.executeWithoutResult(status -> refunds.findByIdForUpdate(refundId)
                        .ifPresent(locked -> locked.clearSending(requestNo)));
                log.warn("환급 입금 요청이 금융망에 가지 않음, 다음 실행에 다시 refundId={}", refundId);
                yield Result.of(Outcome.NOT_SENT);
            }
        };
    }

    /** 읽은 뒤 아무도 바꾸지 않았으면 요청 번호를 남긴다. 이 커밋이 같은 환급을 두 번 보내지 않게 하는 선점이다. */
    private boolean claim(Snapshot read, String requestNo) {
        Refund locked = refunds.findByIdForUpdate(read.refundId()).orElse(null);
        if (locked == null || !locked.isDepositTarget() || !locked.getVersion().equals(read.version())) {
            return false;
        }
        locked.recordSending(requestNo);
        refunds.flush();
        return true;
    }

    /** 입금을 확인한 환급을 완료로 바꾸고 회차를 환급 완료로 바꾼다. 그 사이 다른 실행이 끝냈으면 그대로 둔다. */
    private Outcome complete(Long refundId, String bankTransactionNo) {
        return transaction.execute(status -> {
            Refund locked = refunds.findByIdForUpdate(refundId).orElseThrow();
            if (!locked.isDepositTarget()) {
                log.warn("입금을 확인했는데 환급이 이미 대기가 아님, 운영 확인 refundId={} status={}", refundId, locked.getStatus());
                return Outcome.NEEDS_REVIEW;
            }
            locked.complete(bankTransactionNo, now());
            items.findById(locked.getBillItemId()).orElseThrow().markRefunded();
            refunds.flush();
            items.flush();
            events.publishEvent(new RefundCompletedEvent(refundId, locked.getUserId(), locked.getPaymentTransactionId(),
                    locked.getRefundAmount()));
            return Outcome.COMPLETED;
        });
    }

    /** 계좌가 없어 은행이 거절한 환급을 실패로 끝낸다. 남긴 번호가 이 요청의 것이 아니면(다른 실행이 다시 보냄) 바꾸지 않는다. */
    private Outcome fail(Long refundId, String requestNo, TransferResult.RejectReason reason) {
        return transaction.execute(status -> {
            Refund locked = refunds.findByIdForUpdate(refundId).orElseThrow();
            if (!locked.isDepositTarget() || !requestNo.equals(locked.getFinRequestNo())) {
                log.warn("거절된 요청이 환급의 마지막 요청이 아님, 운영 확인 refundId={} status={}", refundId, locked.getStatus());
                return Outcome.NEEDS_REVIEW;
            }
            locked.fail();
            refunds.flush();
            events.publishEvent(new RefundFailedEvent(refundId, locked.getUserId(), locked.getPaymentTransactionId(),
                    locked.getRefundAmount(), reason));
            return Outcome.FAILED;
        });
    }

    private Outcome needsReview(Long refundId, String what) {
        log.error("환급 입금을 자동으로 판단하지 않음, 운영 확인 refundId={} {}", refundId, what);
        return Outcome.NEEDS_REVIEW;
    }

    private boolean pastGrace(String finRequestNo) {
        return !clock.instant().isBefore(WithdrawalRecovery.requestTime(finRequestNo).plus(properties.recoveryGrace()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** 환급 한 건을 처리한 결과. */
    public enum Outcome {
        /** 입금했거나 이미 들어간 입금을 찾아 완료로 바꿈 */
        COMPLETED,
        /** 계좌가 없어(A1003) 은행이 거절해 실패로 끝냄 */
        FAILED,
        /** 계좌 없음이 아닌 거절. 대기로 두고 운영 로그를 남겼다. 원인을 고치면 다음 실행이 태그 확인 뒤 다시 보낸다 */
        REJECTED,
        /** 보냈지만 결과를 모름. 다음 실행이 확정한다 */
        UNKNOWN,
        /** 요청이 금융망에 가지 않음. 다음 실행이 다시 보낸다 */
        NOT_SENT,
        /** 직전 요청의 유예 안이라 건너뜀 */
        IN_FLIGHT,
        /** 다른 실행이 먼저 바꿔 보내지 않음 */
        CONTENDED,
        /** 자동으로 판단하지 않고 대기로 둠. 운영 로그를 남겼다 */
        NEEDS_REVIEW,
        /** 입금할 환급이 아님(없음·완료·실패·무효·기한 없음) */
        SKIPPED
    }

    /**
     * 환급 한 건의 처리 결과.
     *
     * @param finCode 금융망 거절 코드({@link Outcome#REJECTED}일 때만). 운영 기록용이며 앱 응답에 내보내지 않는다
     */
    public record Result(Outcome outcome, String finCode) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /** 트랜잭션 밖에서 쓰려고 읽어 둔 값. */
    private record Snapshot(Long refundId, Long userId, Long linkedAccountId, long amount, LocalDate dueDate,
            String finRequestNo, Long version, LocalDate createdOn, boolean target) {

        static Snapshot of(Refund refund) {
            LocalDate createdOn = refund.getCreatedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
            return new Snapshot(refund.getId(), refund.getUserId(), refund.getLinkedAccountId(), refund.getRefundAmount(),
                    refund.getDueDate(), refund.getFinRequestNo(), refund.getVersion(), createdOn,
                    refund.isDepositTarget());
        }
    }
}
