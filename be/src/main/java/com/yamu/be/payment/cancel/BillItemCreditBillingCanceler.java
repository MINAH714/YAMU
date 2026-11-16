package com.yamu.be.payment.cancel;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemDiscountReader;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import com.yamu.be.payment.cancel.PaymentCancelResponse.BillItemChange;
import com.yamu.be.payment.cancel.PaymentCancelResponse.RefundLine;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 신용카드 취소의 청구 회차 처리(POL-006). 청구서 상태로 회차마다 처리를 정한다.
 *
 * <pre>
 * 청구서 / 회차                회차             환급
 * ACCUMULATING·미배정 / PENDING → EXCLUDED       없음
 * CONFIRMED / BILLED            → REFUND_PENDING PENDING, 기한 없음(출금 대기). 금액은 예상값
 * PAID / PAID                   → REFUND_PENDING PENDING, 취소일 + 3영업일. 금액은 실제 출금된 몫
 * UNPAID / BILLED               → EXCLUDED       없음. 청구서에 미납 제외액을 쌓는다(남은 미납액이 0원이 되면 청구서 CANCELED)
 * CANCELED / BILLED(0원)        → EXCLUDED       없음. 미납이 정리된 청구서에 남은 0원 회차
 * CLOSED_BY_WITHDRAWAL / 모두    → 그대로         없음. 회원 탈퇴로 닫힌 청구서(S15P31E103-443)
 * </pre>
 *
 * <p>확정된 청구액은 취소해도 그대로 출금한다. 출금 결과 불명은 청구서가 CONFIRMED로 남으므로 출금 대기 환급이 되고, 결과가 확정되면
 * {@link CanceledBillItemSettler}가 금액·기한을 확정하거나 환급을 무효로 바꾼다.
 *
 * <p>금액은 회차의 확정액 몫({@link BillShares}: 회차 청구액 − 확정 때 나눈 청구할인)이다. 아직 취소되지 않은 결제의 회차에 환급이 이미 있으면 기록이 어긋난 것이라
 * 실패시킨다. 0원이면 환급 행 없이 회차만 닫는다. 청구액 일부 출금은
 * 지원하지 않으므로 출금 완료 청구서는 전액 출금, 미납 청구서는 출금 0원이어야 한다. 환급 계좌는 청구서 확정 당시 출금 계좌다.
 * 이 규칙과 맞지 않는 데이터는 일부만 처리하지 않고 실패시켜 취소까지 롤백한다.
 *
 * <p>잠금 순서는 청구서(번호 순) → 회차다. 청구 확정·출금 결과 저장도 청구서부터 잠그면 서로 엇갈리지 않는다. 같은 결제의 동시 취소는
 * 그보다 앞서 결제 행 잠금으로 줄을 서므로 회차·환급은 한 번만 바뀐다(환급은 {@code uk_refund_item}도 막는다).
 */
@Slf4j
@Component
public class BillItemCreditBillingCanceler implements CreditBillingCanceler {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 환급 기한(POL-006 "영업일 기준 3일 내"). 기준일 다음 영업일부터 센다 */
    static final int REFUND_BUSINESS_DAYS = 3;

    private final BillItemRepository items;
    private final CardBillRepository bills;
    private final RefundRepository refunds;
    private final BusinessDayCalendar calendar;
    private final BillItemDiscountReader discounts;

    public BillItemCreditBillingCanceler(BillItemRepository items, CardBillRepository bills, RefundRepository refunds,
            BusinessDayCalendar calendar, BillItemDiscountReader discounts) {
        this.items = items;
        this.bills = bills;
        this.refunds = refunds;
        this.calendar = calendar;
        this.discounts = discounts;
    }

    @Override
    public Result cancel(PaymentTransaction payment, LocalDateTime canceledAt) {
        List<Long> billIds = items.findCardBillIdsByPaymentTransactionId(payment.getId()).stream().sorted().toList();
        Map<Long, CardBill> lockedBills = billIds.isEmpty() ? Map.of()
                : bills.findAllForUpdate(billIds).stream().collect(Collectors.toMap(CardBill::getId, Function.identity()));
        List<BillItem> mine = items.findForCancel(payment.getId());
        Map<Long, Refund> existingRefunds = mine.isEmpty() ? Map.of()
                : refunds.findByBillItemIdIn(mine.stream().map(BillItem::getId).toList()).stream()
                        .collect(Collectors.toMap(Refund::getBillItemId, Function.identity()));
        Map<Long, Map<Long, Long>> sharesByBill = new HashMap<>();

        List<BillItemChange> changes = new ArrayList<>();
        List<Refund> created = new ArrayList<>();
        for (BillItem item : mine) {
            CardBill bill = item.getCardBillId() == null ? null : lockedBills.get(item.getCardBillId());
            if (item.getCardBillId() != null && bill == null) {
                throw mismatch(payment, item, null, "회차의 청구서를 찾지 못했습니다");
            }
            BillItemStatus before = item.getStatus();
            BillStatus billStatus = bill == null ? BillStatus.ACCUMULATING : bill.getStatus();
            if (billStatus == BillStatus.ACCUMULATING && before == BillItemStatus.PENDING) {
                item.exclude();
            } else if (billStatus == BillStatus.CONFIRMED && before == BillItemStatus.BILLED) {
                long amount = refundable(item, share(bill, item, sharesByBill), existingRefunds);
                refundOrClose(payment, item, bill, amount, null, canceledAt, created);
            } else if (billStatus == BillStatus.PAID && before == BillItemStatus.PAID) {
                if (!bill.isFullyPaid()) {
                    throw mismatch(payment, item, bill, "출금 완료 청구서의 출금액이 확정액과 다릅니다(청구액 일부 출금은 지원하지 않음)");
                }
                long amount = refundable(item, share(bill, item, sharesByBill), existingRefunds);
                LocalDate due = amount > 0 ? calendar.plusBusinessDays(koreanDate(canceledAt), REFUND_BUSINESS_DAYS) : null;
                refundOrClose(payment, item, bill, amount, due, canceledAt, created);
            } else if (billStatus == BillStatus.UNPAID && before == BillItemStatus.BILLED) {
                if (bill.getPaidAmount() != 0) {
                    throw mismatch(payment, item, bill, "미납 청구서에 출금액이 있습니다(청구액 일부 출금은 지원하지 않음)");
                }
                long share = share(bill, item, sharesByBill);
                item.waiveUnpaid(canceledAt);
                if (share > 0) {
                    bill.waiveUnpaidPrincipal(share);
                }
            } else if (billStatus == BillStatus.CANCELED && before == BillItemStatus.BILLED) {
                // 다른 결제를 모두 취소해 남은 미납액이 0원이 된 청구서에 남은 회차. 남은 미납액이 0원이므로 이 회차 몫도 0원뿐이다
                if (share(bill, item, sharesByBill) != 0) {
                    throw mismatch(payment, item, bill, "미납이 정리된 청구서에 금액이 남은 회차가 있습니다");
                }
                item.waiveUnpaid(canceledAt);
            } else if (billStatus == BillStatus.CLOSED_BY_WITHDRAWAL) {
                // 회원 탈퇴로 YAMU가 더 다루지 않는 청구서(S15P31E103-443). 회차·환급을 건드리지 않는다. 출금을 기다리지 않으므로
                // 환급을 만들면 영원히 대기로 남는다
                log.info("탈퇴로 닫힌 청구서의 회차라 취소의 청구 처리를 건너뜀 paymentId={} billItemId={} cardBillId={}",
                        payment.getId(), item.getId(), bill.getId());
            } else {
                throw mismatch(payment, item, bill, "청구서와 회차 상태가 맞지 않습니다");
            }
            changes.add(new BillItemChange(item.getId(), item.getInstallmentNo(), billedAmount(item), before.name(),
                    item.getStatus().name()));
        }
        items.flush();
        bills.flush();
        List<RefundLine> lines = refunds.saveAllAndFlush(created).stream()
                .map(refund -> new RefundLine(refund.getId(), refund.getBillItemId(), refund.getRefundAmount(),
                        refund.getStatus().name(), refund.getDueDate(), refund.getLinkedAccountId()))
                .toList();
        return new Result(changes, lines);
    }

    /** 확정액 중 이 회차 몫. 청구서마다 한 번만 계산한다(청구서를 잠근 뒤 읽으므로 그 사이 바뀌지 않는다). */
    private long share(CardBill bill, BillItem item, Map<Long, Map<Long, Long>> sharesByBill) {
        Map<Long, Long> shares = sharesByBill.computeIfAbsent(bill.getId(),
                id -> BillShares.of(bill, items.findByCardBillIdIn(List.of(id)), discounts.amountsByItem(id)));
        Long share = shares.get(item.getId());
        if (share == null) {
            throw new IllegalStateException("확정액에 들어가지 않은 회차입니다 (회차 " + item.getId() + ")");
        }
        return share;
    }

    /**
     * 몫에서 이미 만든 환급을 뺀 금액. 환급은 취소 때만 회차당 한 행 생기므로({@code uk_refund_item}), 아직 취소되지 않은 결제의
     * 회차에 환급(대기·진행·완료·실패·무효 무엇이든)이 있으면 기록이 어긋난 것이다. 다시 만들거나 덮어쓰지 않고 실패시킨다.
     */
    private static long refundable(BillItem item, long share, Map<Long, Refund> existingRefunds) {
        Refund existing = existingRefunds.get(item.getId());
        if (existing != null) {
            throw new IllegalStateException("취소 전 결제의 회차에 이미 환급이 있습니다 (회차 " + item.getId() + ", 환급 "
                    + existing.getId() + " " + existing.getStatus() + " " + existing.getRefundAmount() + ")");
        }
        return share;
    }

    /** 돌려줄 금액이 있으면 확정 당시 출금 계좌로 환급 대기를 만들고, 0원이면 회차만 닫는다. */
    private static void refundOrClose(PaymentTransaction payment, BillItem item, CardBill bill, long amount,
            LocalDate dueDate, LocalDateTime canceledAt, List<Refund> created) {
        if (amount <= 0) {
            item.closeWithoutRefund(canceledAt);
            return;
        }
        if (bill.getLinkedAccountId() == null) {
            throw new IllegalStateException("확정된 청구서의 출금 계좌가 없습니다 (청구서 " + bill.getId() + ")");
        }
        item.awaitRefund();
        created.add(Refund.pending(payment.getId(), item.getId(), payment.getUserId(), bill.getLinkedAccountId(), amount,
                dueDate));
    }

    private static IllegalStateException mismatch(PaymentTransaction payment, BillItem item, CardBill bill, String why) {
        return new IllegalStateException(why + " (결제 " + payment.getId() + ", 회차 " + item.getId() + " " + item.getStatus()
                + ", 청구서 " + item.getCardBillId() + " " + (bill == null ? null : bill.getStatus()) + ")");
    }

    static LocalDate koreanDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
    }

    /** 무이자라 청구액 = 원금이다. 청구액 미산정(NULL) 회차는 원금으로 보여 준다. */
    private static long billedAmount(BillItem item) {
        return item.getBilledAmount() != null ? item.getBilledAmount() : item.getPrincipalAmount();
    }
}
