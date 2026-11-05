package com.yamu.be.payment.billing.withdraw;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.withdraw.BillAccountReader.BankAccess;
import com.yamu.be.payment.cancel.CanceledBillItemSettler;
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
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 확정 청구서 한 건의 결제일 출금(Task 5.4.3, POL-005, 2026-11-05 결정). 금융망을 부르는 동안에는 트랜잭션·DB 잠금을 잡지 않는다.
 *
 * <pre>
 * ① 요청 번호가 없으면: 출금일 전이면 건너뜀. 출금일이 확정 뒤 공휴일이 됐으면 다음 영업일로 미룸
 *    요청 번호가 있으면(결과를 기다림): 그 번호의 시각 + 유예(approve.recovery-grace) 안이면 건너뜀. SSAFY가 아직 처리 중일 수 있다
 * ② 출금 계좌: 청구서에 저장한 확정 당시 계좌(끊겼어도 그 계좌)
 * ③ 이미 나갔는지: 출금일 전날 ~ 오늘의 출금 내역에서 YAMU-B{청구서 번호}를 모두 찾는다(이 기간이 이 청구서의 모든 요청을 덮는다).
 *    1건이고 금액이 확정액과 같으면 PAID로 바꾸고 보내지 않는다(출금 뒤 저장 실패, 결과 불명 뒤 늦은 처리를 같이 막는다)
 * ④ 없으면: 첫 출금이면 새 요청 번호를 청구서에 먼저 커밋하고 출금. 요청한 출금이 유예 뒤에도 없으면 새 번호로 한 번만 다시 보내고
 *    다시 보낸 시각을 남긴다. 다시 보낸 출금도 없으면 더 보내지 않고 CONFIRMED로 둔 채 운영 로그
 * ⑤ 성공 → PAID·회차 PAID·출금 전 취소 환급 확정(기한 = 출금일 + 3영업일)·이벤트
 *    은행 거절(모든 REJECTED) → UNPAID·출금 전 취소 환급 무효·이벤트. 자동으로 다시 출금하지 않는다
 *    결과 불명 → 그대로(다음 실행이 ①③으로 확정) / 요청 안 감 → 남긴 번호를 지운다
 * </pre>
 *
 * <p>같은 청구서를 두 실행이 동시에 다루면 ④에서 청구서를 잠그고 버전·요청 번호를 비교해 한쪽만 보낸다. 늦은 쪽은 남긴 번호를 보고 ①에서
 * 멈춘다. 결과 저장도 청구서 잠금 안에서 상태를 다시 보므로 두 번 저장하지 않는다. 잠금 순서는 청구서 → 회차 → 환급으로 취소와 같다.
 * 자동으로 판단하지 않는 경우(태그 2건 이상·금액 다름·계좌나 회원 키를 읽을 수 없음·재시도도 확인 안 됨)는 CONFIRMED로 두고
 * 운영 로그를 남긴다. 로그에는 청구서 번호·태그·금액·결과만 남긴다(계좌번호·userKey 금지).
 *
 * <p>한계: 출금 응답이 유예(30초, 응답 제한 10초)보다 늦게 오는 사이 다른 실행이 ③을 지나 다시 보내면 두 번 나갈 수 있다. 은행이 유예
 * 뒤에 첫 출금을 처리하면 재시도와 함께 두 번 나갈 수 있다(결정 (1)이 감수한 위험). 그때는 ③이 2건을 보고 운영 확인으로 남긴다.
 */
@Slf4j
@Component
public class BillWithdrawer {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CardBillRepository bills;
    private final BillItemRepository items;
    private final BillAccountReader accounts;
    private final SsafyTransferApi transfer;
    private final CanceledBillItemSettler settler;
    private final BusinessDayCalendar calendar;
    private final PaymentApprovalProperties approval;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BillWithdrawer(CardBillRepository bills, BillItemRepository items, BillAccountReader accounts,
            SsafyTransferApi transfer, CanceledBillItemSettler settler, BusinessDayCalendar calendar,
            PaymentApprovalProperties approval, ApplicationEventPublisher events, TransactionTemplate transaction,
            Clock clock) {
        this.bills = bills;
        this.items = items;
        this.accounts = accounts;
        this.transfer = transfer;
        this.settler = settler;
        this.calendar = calendar;
        this.approval = approval;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * 청구서 하나를 출금하거나 출금 결과를 확인한다. 금융망 조회·채번 실패는 아무것도 바꾸지 않은 결과로 돌려준다. 그 밖의 예외(DB 저장,
     * 환급 정리의 기록 어긋남 등)는 그대로 던진다. 그때도 요청 번호를 남긴 뒤라면 다음 실행이 거래내역으로 확인한다.
     *
     * @param asOf         대상을 고르는 기준일(한국 날짜). 출금 예정일이 이 날 이하일 때만 새로 출금한다
     * @param inFlightOnly true면 이미 출금을 요청한 청구서만 확인·재시도한다(결과 불명 복구). 새 출금은 하지 않는다
     */
    public Result withdraw(Long cardBillId, LocalDate asOf, boolean inFlightOnly) {
        Snapshot bill = read(cardBillId);
        if (bill == null || bill.status() != BillStatus.CONFIRMED) {
            return Result.of(Kind.SKIPPED, cardBillId, bill == null ? null : bill.userId(), null, null,
                    "이미 출금 결과가 정해진 청구서입니다");
        }
        if (bill.finRequestNo() == null) {
            if (inFlightOnly) {
                return Result.of(Kind.SKIPPED, bill, "아직 출금을 요청하지 않은 청구서입니다");
            }
            if (bill.withdrawalDate().isAfter(asOf)) {
                return Result.of(Kind.SKIPPED, bill, "출금 예정일 전입니다");
            }
            if (!calendar.isBusinessDay(bill.withdrawalDate())) {
                LocalDate moved = postpone(cardBillId);
                if (moved == null) {
                    return Result.of(Kind.CONTENDED, bill, "다른 실행이 먼저 청구서를 바꿨습니다");
                }
                if (moved.isAfter(asOf)) {
                    return Result.of(Kind.POSTPONED, bill, "출금 예정일이 공휴일이라 " + moved + "로 미뤘습니다");
                }
                bill = read(cardBillId);
                if (bill == null || bill.status() != BillStatus.CONFIRMED || bill.finRequestNo() != null) {
                    return Result.of(Kind.CONTENDED, cardBillId, null, null, null, "다른 실행이 먼저 청구서를 바꿨습니다");
                }
            }
        } else if (!pastGrace(bill.finRequestNo())) {
            log.info("청구 출금 결과 확인 대기 중이라 건너뜀 cardBillId={}", cardBillId);
            return Result.of(Kind.IN_FLIGHT, bill, "출금 결과를 기다리는 중입니다");
        }

        BankAccess access = accounts.forBill(bill.userId(), bill.linkedAccountId()).orElse(null);
        if (access == null) {
            return needsReview(bill, "출금 계좌나 SSAFY 회원 키를 읽을 수 없음");
        }
        TransferTag tag = TransferTag.bill(cardBillId);
        List<BankTransaction> found;
        try {
            found = transfer.findAllByTag(access.userKey(), access.accountNo(), tag,
                    bill.withdrawalDate().minusDays(1), today());
        } catch (BusinessException e) {
            log.warn("청구 출금 확인용 거래내역 조회 실패, 다음 실행에 다시 cardBillId={} code={}", cardBillId,
                    e.getErrorCode());
            return Result.of(Kind.LOOKUP_FAILED, bill, "거래내역 조회에 실패했습니다. 다음 실행에 다시 확인합니다");
        }
        if (found.size() > 1) {
            return needsReview(bill, "청구 출금이 " + found.size() + "건(두 번 나갔을 수 있음)");
        }
        if (found.size() == 1) {
            BankTransaction withdrawn = found.get(0);
            if (withdrawn.amount() != bill.amount()) {
                return needsReview(bill, "출금액 " + withdrawn.amount() + "원이 확정액 " + bill.amount() + "원과 다름");
            }
            log.info("청구 출금을 거래내역에서 찾음, 다시 보내지 않음 cardBillId={} tag={}", cardBillId, tag);
            return paid(cardBillId, withdrawn.bankTransactionNo(), withdrawn.date());
        }

        boolean retry = bill.finRequestNo() != null;
        if (retry && bill.retried()) {
            log.error("다시 보낸 청구 출금도 거래내역에 없어 더 보내지 않음, CONFIRMED 유지 운영 확인 cardBillId={} tag={}",
                    cardBillId, tag);
            return Result.of(Kind.NEEDS_REVIEW, bill, "다시 보낸 출금도 확인되지 않아 더 보내지 않습니다(운영 확인)");
        }
        if (retry) {
            log.warn("요청한 청구 출금이 유예 뒤에도 거래내역에 없어 새 번호로 한 번 다시 보냄 cardBillId={} tag={}", cardBillId,
                    tag);
        }
        String requestNo;
        try {
            requestNo = transfer.newTransactionNo();
        } catch (BusinessException e) {
            log.warn("청구 출금 요청 번호 채번 실패, 다음 실행에 다시 cardBillId={} code={}", cardBillId, e.getErrorCode());
            return Result.of(Kind.NOT_SENT, bill, "출금 요청 번호를 받지 못했습니다. 다음 실행에 다시 보냅니다");
        }
        Snapshot claimedFrom = bill;
        if (!Boolean.TRUE.equals(transaction.execute(status -> claim(claimedFrom, requestNo, retry)))) {
            log.info("다른 실행이 먼저 청구서를 바꿈, 보내지 않음 cardBillId={}", cardBillId);
            return Result.of(Kind.CONTENDED, bill, "다른 실행이 먼저 청구서를 바꿨습니다");
        }

        TransferResult result = transfer.withdraw(access.userKey(), access.accountNo(), bill.amount(), tag, requestNo);
        return switch (result.status()) {
            case SUCCESS -> {
                log.info("청구 출금 성공 cardBillId={} tag={} amount={}", cardBillId, tag, bill.amount());
                yield paid(cardBillId, result.bankTransactionNo(), result.transactionDate());
            }
            case REJECTED -> {
                log.warn("청구 출금 거절, 미납으로 바꿈 cardBillId={} reason={} code={}", cardBillId, result.rejectReason(),
                        result.finCode());
                yield unpaid(cardBillId, requestNo, result.rejectReason());
            }
            case UNKNOWN -> {
                // 돈이 나갔을 수 있다. 남긴 번호로 유예를 지킨 뒤 다음 실행이 거래내역의 태그로 확정한다
                log.warn("청구 출금 결과 불명 cardBillId={} tag={} code={}", cardBillId, tag, result.finCode());
                yield Result.of(Kind.UNKNOWN, bill, "출금 결과를 모릅니다. 거래내역으로 확인합니다");
            }
            case NOT_SENT -> {
                transaction.executeWithoutResult(status -> lock(cardBillId).clearUnsentRequest(requestNo));
                log.warn("청구 출금 요청이 금융망에 가지 않음, 다음 실행에 다시 cardBillId={}", cardBillId);
                yield Result.of(Kind.NOT_SENT, bill, "출금 요청이 금융망에 가지 않았습니다. 다음 실행에 다시 보냅니다");
            }
        };
    }

    /** 읽은 뒤 아무도 바꾸지 않았으면 요청 번호를 남긴다. 이 커밋이 같은 청구서를 두 번 보내지 않게 하는 선점이다. */
    private boolean claim(Snapshot read, String requestNo, boolean retry) {
        CardBill locked = lock(read.cardBillId());
        if (locked.getStatus() != BillStatus.CONFIRMED || !locked.getVersion().equals(read.version())
                || !Objects.equals(locked.getFinRequestNo(), read.finRequestNo())) {
            return false;
        }
        if (retry) {
            locked.retryWithdrawal(requestNo, now());
        } else {
            locked.requestWithdrawal(requestNo);
        }
        bills.flush();
        return true;
    }

    /**
     * 출금을 확인한 청구서를 PAID로 바꾸고, 확정 회차를 출금 완료로, 출금 전에 취소한 회차의 환급을 확정한다. 한 트랜잭션이라 환급
     * 확정이 실패하면 PAID도 롤백되고 다음 실행이 거래내역으로 다시 확인한다(다시 보내지 않는다).
     */
    private Result paid(Long cardBillId, String bankTransactionNo, LocalDate withdrawnOn) {
        return transaction.execute(status -> {
            CardBill locked = lock(cardBillId);
            if (locked.getStatus() != BillStatus.CONFIRMED) {
                log.warn("출금을 확인했는데 청구서가 이미 확정 상태가 아님 cardBillId={} status={}", cardBillId,
                        locked.getStatus());
                return Result.of(Kind.CONTENDED, cardBillId, locked.getUserId(), null, null,
                        "다른 실행이 먼저 출금 결과를 저장했습니다(" + locked.getStatus() + ")");
            }
            long amount = locked.getConfirmedAmount();
            locked.markPaid(amount, bankTransactionNo, now());
            for (BillItem item : items.findByCardBillIdIn(List.of(cardBillId))) {
                if (item.getStatus() == BillItemStatus.BILLED) {
                    item.markPaid();
                }
            }
            items.flush();
            bills.flush();
            settler.onWithdrawalSucceeded(cardBillId, locked.getLinkedAccountId(), withdrawnOn);
            events.publishEvent(new BillWithdrawnEvent(cardBillId, locked.getUserId(), locked.getUserCardId(), amount,
                    withdrawnOn, bankTransactionNo));
            return new Result(Kind.PAID, cardBillId, locked.getUserId(), BillStatus.PAID, amount, bankTransactionNo,
                    null);
        });
    }

    /**
     * 은행이 거절한 출금을 미납으로 바꾸고, 출금 전에 취소한 회차의 환급을 무효로 하며 그 몫을 미납 대상에서 뺀다. 남긴 번호가 이 요청의
     * 것이 아니면(다른 실행이 다시 보냄) 바꾸지 않는다.
     */
    private Result unpaid(Long cardBillId, String requestNo, TransferResult.RejectReason reason) {
        return transaction.execute(status -> {
            CardBill locked = lock(cardBillId);
            if (locked.getStatus() != BillStatus.CONFIRMED || !requestNo.equals(locked.getFinRequestNo())) {
                log.error("거절된 요청이 청구서의 마지막 출금 요청이 아님, 운영 확인 cardBillId={} status={}", cardBillId,
                        locked.getStatus());
                return Result.of(Kind.NEEDS_REVIEW, cardBillId, locked.getUserId(), null, null,
                        "거절된 출금 요청이 청구서의 마지막 요청이 아닙니다(운영 확인)");
            }
            LocalDateTime now = now();
            locked.markUnpaid(now);
            bills.flush();
            settler.onWithdrawalFailed(cardBillId, now);
            long unpaid = locked.outstandingUnpaid();
            if (locked.getStatus() == BillStatus.UNPAID) {
                events.publishEvent(new BillUnpaidEvent(cardBillId, locked.getUserId(), locked.getUserCardId(), unpaid,
                        reason));
            } else {
                log.info("출금 거절 뒤 출금 전 취소로 남은 미납액이 0원이라 미납 알림 없음 cardBillId={} status={}", cardBillId,
                        locked.getStatus());
            }
            return new Result(Kind.UNPAID, cardBillId, locked.getUserId(), locked.getStatus(), unpaid, null,
                    "출금 거절: " + reason);
        });
    }

    /** 확정 뒤 공휴일이 된 출금 예정일을 다음 영업일로 미룬다. 그 사이 다른 실행이 바꿨으면 그대로 두고 null. */
    private LocalDate postpone(Long cardBillId) {
        return transaction.execute(status -> {
            CardBill locked = lock(cardBillId);
            if (locked.getStatus() != BillStatus.CONFIRMED || locked.getFinRequestNo() != null
                    || calendar.isBusinessDay(locked.getWithdrawalDate())) {
                return null;
            }
            LocalDate before = locked.getWithdrawalDate();
            LocalDate after = calendar.businessDayOnOrAfter(before);
            locked.postponeWithdrawal(after);
            bills.flush();
            log.info("출금 예정일이 확정 뒤 공휴일이 되어 미룸 cardBillId={} before={} after={}", cardBillId, before, after);
            return after;
        });
    }

    private Snapshot read(Long cardBillId) {
        return transaction.execute(status -> bills.findById(cardBillId).map(Snapshot::of).orElse(null));
    }

    private CardBill lock(Long cardBillId) {
        return bills.findAllForUpdate(List.of(cardBillId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("청구서가 없습니다 (청구서 " + cardBillId + ")"));
    }

    private Result needsReview(Snapshot bill, String what) {
        log.error("청구 출금을 자동으로 판단하지 않음, CONFIRMED 유지 운영 확인 cardBillId={} {}", bill.cardBillId(), what);
        return Result.of(Kind.NEEDS_REVIEW, bill, what + "(운영 확인)");
    }

    private boolean pastGrace(String finRequestNo) {
        return !clock.instant().isBefore(requestTime(finRequestNo).plus(approval.recoveryGrace()));
    }

    /**
     * 기관거래고유번호의 앞 14자리는 채번한 때의 한국 시각이다(스키마 사전 「금융망 요청 번호 채번 규칙」). 결제 복구
     * ({@code WithdrawalRecovery#requestTime})와 같은 규칙이다. 그 메서드가 패키지 밖에 열리면 그것을 쓴다.
     */
    static Instant requestTime(String finRequestNo) {
        try {
            return LocalDateTime.parse(finRequestNo.substring(0, 14), REQUEST_TIME).atZone(KST).toInstant();
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("기관거래고유번호의 시각을 읽을 수 없습니다", e);
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** 청구서 한 건을 처리한 결과. */
    public enum Kind {
        /** 출금했거나 이미 나간 출금을 찾아 PAID로 바꿈 */
        PAID,
        /** 은행이 거절해 미납(출금 전 취소로 남은 미납액이 0원이면 CANCELED)으로 바꿈 */
        UNPAID,
        /** 보냈지만 결과를 모름. 다음 실행이 확정한다 */
        UNKNOWN,
        /** 요청 번호를 받지 못했거나 요청이 금융망에 가지 않음. 다음 실행이 다시 보낸다 */
        NOT_SENT,
        /** 직전 요청의 유예 안이라 건너뜀 */
        IN_FLIGHT,
        /** 출금 예정일이 공휴일이 되어 다음 영업일로 미룸 */
        POSTPONED,
        /** 다른 실행이 먼저 바꿔 아무것도 하지 않음 */
        CONTENDED,
        /** 출금할 청구서가 아님(결과 확정됨·출금일 전·요청 전) */
        SKIPPED,
        /** 거래내역 조회가 실패해 판단하지 않음. 다음 실행에 다시 */
        LOOKUP_FAILED,
        /** 자동으로 판단하지 않고 CONFIRMED로 둠. 운영 로그를 남겼다 */
        NEEDS_REVIEW
    }

    /**
     * @param status           처리 뒤 청구서 상태. 바꾸지 않았으면 null
     * @param amount           출금액(PAID) 또는 남은 미납액(UNPAID). 그 밖에는 확정액이거나 null
     * @param finTransactionNo 금융망 출금 거래 번호. PAID일 때만
     * @param message          건너뛰거나 판단하지 않은 이유
     */
    public record Result(Kind kind, Long cardBillId, Long userId, BillStatus status, Long amount,
            String finTransactionNo, String message) {

        static Result of(Kind kind, Long cardBillId, Long userId, BillStatus status, Long amount, String message) {
            return new Result(kind, cardBillId, userId, status, amount, null, message);
        }

        static Result of(Kind kind, Snapshot bill, String message) {
            return new Result(kind, bill.cardBillId(), bill.userId(), null, bill.amount(), null, message);
        }
    }

    /** 트랜잭션 밖에서 쓰려고 읽어 둔 값. */
    private record Snapshot(Long cardBillId, Long userId, Long linkedAccountId, BillStatus status, long amount,
            LocalDate withdrawalDate, String finRequestNo, boolean retried, Long version) {

        static Snapshot of(CardBill bill) {
            return new Snapshot(bill.getId(), bill.getUserId(), bill.getLinkedAccountId(), bill.getStatus(),
                    bill.getConfirmedAmount() == null ? 0 : bill.getConfirmedAmount(), bill.getWithdrawalDate(),
                    bill.getFinRequestNo(), bill.isWithdrawalRetried(), bill.getVersion());
        }
    }
}
