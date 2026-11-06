package com.yamu.be.payment.cancel;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구서 출금 결과가 확정된 뒤, 출금 전에 취소한 회차(REFUND_PENDING + 출금 대기 환급)를 정리한다(POL-006).
 * 결제일 출금(S15P31E103-207, {@code BillWithdrawer})이 출금 결과를 청구서에 저장한 트랜잭션 안에서 부른다.
 *
 * <ul>
 *   <li>출금 성공: 환급 금액을 실제 출금된 몫으로 확정하고 기한을 출금 성공일 + 3영업일로 정한다. 환급 계좌가 실제 출금 계좌와 같은지 본다.</li>
 *   <li>출금 실패(미납) 확정: 돌려줄 돈이 없으므로 환급을 무효(CANCELED)로 바꾸고, 회차 몫을 미납 대상에서 뺀다. 다른 결제의 미납액은 그대로다.
 *       청구서의 결제를 모두 출금 전에 취소했으면 남은 미납액이 0원이 되어 청구서도 CANCELED가 된다.</li>
 *   <li>결과 불명: 부르지 않는다. 청구서가 CONFIRMED로 남아 환급은 출금 대기 그대로이고 입금하지 않는다.</li>
 * </ul>
 * 잠금 순서는 취소와 같이 청구서 → 회차다. 같은 청구서의 취소와 엇갈려도 먼저 잠근 쪽이 끝난 뒤 다른 쪽이 바뀐 상태를 읽는다.
 */
@Slf4j
@Component
public class CanceledBillItemSettler {

    private final BillItemRepository items;
    private final CardBillRepository bills;
    private final RefundRepository refunds;
    private final BusinessDayCalendar calendar;

    public CanceledBillItemSettler(BillItemRepository items, CardBillRepository bills, RefundRepository refunds,
            BusinessDayCalendar calendar) {
        this.items = items;
        this.bills = bills;
        this.refunds = refunds;
        this.calendar = calendar;
    }

    /**
     * 출금 성공으로 저장한 청구서(PAID, 확정액 전부 출금)의 출금 대기 환급을 확정한다.
     *
     * @param withdrawnAccountId 실제로 출금한 계좌
     * @param withdrawnOn        출금 성공일(한국 날짜, 금융망 거래일)
     * @return 확정한 환급
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Refund> onWithdrawalSucceeded(Long cardBillId, Long withdrawnAccountId, LocalDate withdrawnOn) {
        CardBill bill = lock(cardBillId);
        if (!bill.isFullyPaid()) {
            throw new IllegalStateException("확정액 전부 출금된 청구서가 아닙니다 (청구서 " + cardBillId + ", 상태 " + bill.getStatus() + ")");
        }
        if (!withdrawnAccountId.equals(bill.getLinkedAccountId())) {
            throw new IllegalStateException("실제 출금 계좌가 청구서 확정 당시 출금 계좌와 다릅니다 (청구서 " + cardBillId + ")");
        }
        List<BillItem> billItems = items.findByCardBillIdIn(List.of(cardBillId));
        Map<Long, Long> shares = BillShares.of(bill, billItems);
        LocalDate due = calendar.plusBusinessDays(withdrawnOn, BillItemCreditBillingCanceler.REFUND_BUSINESS_DAYS);
        List<Refund> confirmed = new ArrayList<>();
        for (Refund refund : awaiting(cardBillId, billItems)) {
            if (!withdrawnAccountId.equals(refund.getLinkedAccountId())) {
                throw new IllegalStateException("환급 계좌가 실제 출금 계좌와 다릅니다 (환급 " + refund.getId() + ")");
            }
            refund.confirmAfterWithdrawal(shares.get(refund.getBillItemId()), due);
            confirmed.add(refund);
        }
        refunds.flush();
        log.info("출금 성공 뒤 취소 회차 환급 확정 cardBillId={} refunds={} due={}", cardBillId, confirmed.size(), due);
        return confirmed;
    }

    /**
     * 출금 실패(미납)로 저장한 청구서(UNPAID, 출금 0원)의 출금 대기 환급을 무효로 하고 회차 몫을 미납 대상에서 뺀다.
     *
     * @return 무효로 바꾼 환급
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Refund> onWithdrawalFailed(Long cardBillId, LocalDateTime failedAt) {
        CardBill bill = lock(cardBillId);
        if (bill.getStatus() != BillStatus.UNPAID || bill.getPaidAmount() != 0) {
            throw new IllegalStateException("출금 0원의 미납 청구서가 아닙니다 (청구서 " + cardBillId + ", 상태 " + bill.getStatus() + ")");
        }
        List<BillItem> billItems = items.findByCardBillIdIn(List.of(cardBillId));
        Map<Long, Long> shares = BillShares.of(bill, billItems);
        Map<Long, BillItem> byId = billItems.stream().collect(Collectors.toMap(BillItem::getId, Function.identity()));
        List<Refund> voided = new ArrayList<>();
        for (Refund refund : awaiting(cardBillId, billItems)) {
            refund.voidForUnpaid();
            byId.get(refund.getBillItemId()).waiveUnpaid(failedAt);
            long share = shares.get(refund.getBillItemId());
            if (share > 0) {
                bill.waiveUnpaidPrincipal(share);
            }
            voided.add(refund);
        }
        items.flush();
        bills.flush();
        refunds.flush();
        log.info("출금 실패 뒤 취소 회차 환급 무효 cardBillId={} refunds={}", cardBillId, voided.size());
        return voided;
    }

    private CardBill lock(Long cardBillId) {
        return bills.findAllForUpdate(List.of(cardBillId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("청구서가 없습니다 (청구서 " + cardBillId + ")"));
    }

    /**
     * 출금 전에 취소된 회차의 출금 대기 환급. REFUND_PENDING 회차는 출금 대기 환급이거나(출금 전 취소) 기한이 정해진 환급(출금 뒤 취소)을
     * 하나씩 가져야 한다. 출금 결과가 나오기 전인데 기한이 정해진 환급이 있으면 기록이 어긋난 것이다.
     */
    private List<Refund> awaiting(Long cardBillId, List<BillItem> billItems) {
        List<Long> canceled = billItems.stream()
                .filter(item -> item.getStatus() == BillItemStatus.REFUND_PENDING)
                .map(BillItem::getId)
                .toList();
        if (canceled.isEmpty()) {
            return List.of();
        }
        List<Refund> found = refunds.findByBillItemIdIn(canceled);
        if (found.size() != canceled.size()) {
            throw new IllegalStateException("환급 대기 회차에 환급 행이 없습니다 (청구서 " + cardBillId + ")");
        }
        for (Refund refund : found) {
            if (!refund.isAwaitingWithdrawal()) {
                throw new IllegalStateException("출금 결과 전인데 출금 대기가 아닌 환급이 있습니다 (환급 " + refund.getId() + ", 상태 "
                        + refund.getStatus() + ")");
            }
        }
        return found;
    }
}
